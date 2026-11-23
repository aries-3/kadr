"""Computes the reference the Java side of the model is checked against, with Hugging Face
transformers and PyTorch running the original model, not its ONNX export:

- the pixels of each test image as Pillow decodes it, upright and in RGB, as a SHA-256 digest;
- the vector of each test image, prepared by the model's own image processor;
- the token ids and the vector of each test text, lowercased and padded to 64 tokens, as SigLIP 2
  was trained.

    python reference.py <images folder> <output json>

The output is committed next to the images; run again only when the images or the texts change.
"""
import hashlib
import json
import sys
from pathlib import Path

import PIL
import torch
import transformers
from PIL import Image, ImageOps
from transformers import AutoModel, AutoTokenizer, SiglipImageProcessorPil

MODEL = "google/siglip2-base-patch16-224"
REVISION = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
TEXT_LENGTH = 64

TEXTS = [
    "a dog on the beach",
    "Mountains At Sunset",
    "a lake in a green meadow under a blue sky",
    "2 cats & 1 dog, 2019!",
    "собака на пляже",
    "Горы на закате, снег на вершинах",
    "чек из магазина",
    "Ein Hund am Strand",
    "a long walk along the shore of a quiet lake at dawn, with mist over the water, pine trees on "
    "the far bank, a wooden boat tied to a small pier, ducks swimming near the reeds and the first "
    "rays of the sun lighting the tops of the mountains behind the forest",
    "длинная прогулка по берегу тихого озера на рассвете: туман над водой, сосны на дальнем берегу, "
    "деревянная лодка у маленького причала, утки плавают у камышей, а первые лучи солнца освещают "
    "вершины гор за лесом",
]


def upright_rgb(path):
    """As Pillow shows it: turned by the EXIF orientation, transparency dropped."""
    return ImageOps.exif_transpose(Image.open(path)).convert("RGB")


def unit(vectors):
    return torch.nn.functional.normalize(vectors, dim=-1)


def floats(vector):
    return [float(f"{value:.9g}") for value in vector.tolist()]


def main(images_folder, output):
    torch.set_grad_enabled(False)
    # Resizes with Pillow, as the image processors of transformers 4 did; the default one of
    # transformers 5 resizes with torchvision, which gives slightly different pixels
    processor = SiglipImageProcessorPil.from_pretrained(MODEL, revision=REVISION)
    tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION)
    model = AutoModel.from_pretrained(MODEL, revision=REVISION, dtype=torch.float32).eval()

    images = []
    for path in sorted(images_folder.iterdir()):
        image = upright_rgb(path)
        pixel_values = processor(images=image, return_tensors="pt")["pixel_values"]
        features = model.get_image_features(pixel_values=pixel_values)
        features = getattr(features, "pooler_output", features)
        images.append({
            "file": path.name,
            "width": image.width,
            "height": image.height,
            "pixels_sha256": hashlib.sha256(image.tobytes()).hexdigest(),
            "embedding": floats(unit(features)[0]),
        })

    # SigLIP was trained without an attention mask: the model gets the token ids only
    tokens = tokenizer([text.lower() for text in TEXTS], padding="max_length", max_length=TEXT_LENGTH,
                       truncation=True, return_tensors="pt")
    features = model.get_text_features(input_ids=tokens["input_ids"])
    features = unit(getattr(features, "pooler_output", features))
    texts = [{"text": text, "input_ids": ids.tolist(), "embedding": floats(vector)}
             for text, ids, vector in zip(TEXTS, tokens["input_ids"], features)]

    versions = {"torch": torch.__version__, "transformers": transformers.__version__, "pillow": PIL.__version__}
    # An image or a text a line: the file stays short and a change shows which item it touched
    lines = ["{",
             f' "model": {json.dumps(MODEL)},',
             f' "revision": {json.dumps(REVISION)},',
             f' "versions": {json.dumps(versions)},',
             ' "images": [',
             ",\n".join("  " + json.dumps(image, ensure_ascii=False) for image in images),
             " ],",
             ' "texts": [',
             ",\n".join("  " + json.dumps(text, ensure_ascii=False) for text in texts),
             " ]",
             "}"]
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
