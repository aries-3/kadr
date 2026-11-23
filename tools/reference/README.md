# Reference check of the model

The service runs SigLIP 2 in Java: images decoded with ImageIO, resized and normalized by its own code, the ONNX export of the model on ONNX Runtime, texts tokenized through DJL. A mistake in any of these gives no error, only vectors a little off, and search gets quietly worse. So the Java side is checked against the original model run by its authors' tools: Hugging Face transformers and PyTorch, images decoded by Pillow.

- `make_test_images.py` makes the test images: small generated scenes in the formats and color modes that need care — JPEG in RGB, grayscale and CMYK, PNG with transparency and with a palette, WebP, a photo stored on its side with an EXIF orientation, an image smaller than the model input. Needs only Pillow.
- `reference.py` computes, with the original model, the pixels of each image as Pillow decodes it (a SHA-256 digest), the vector of each image and the token ids and the vector of each test text.

Both write into `ml-onnx/src/test/resources/reference/`, which is committed; `ReferencePixelsTest` and `ReferenceEmbeddingsIT` compare the Java side with it. Run the scripts again only to change the images or the texts.

```bash
python3 -m venv /tmp/kadr-reference
/tmp/kadr-reference/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0
/tmp/kadr-reference/bin/pip install -r requirements.txt

cd ../..
/tmp/kadr-reference/bin/python tools/reference/make_test_images.py ml-onnx/src/test/resources/reference/images
/tmp/kadr-reference/bin/python tools/reference/reference.py \
    ml-onnx/src/test/resources/reference/images ml-onnx/src/test/resources/reference/reference.json
```

`reference.py` downloads the original model, about 1.5 GB, into the Hugging Face cache, and needs about 1.5 GB of memory. transformers warns that `bos_token_id` and `eos_token_id` of the model config are outside the vocabulary: the config keeps defaults of the first SigLIP, and nothing here generates text, so they aren't used.

Results when it was made: every text and every image but the WebP one match to six places; the WebP one, 0.994. Pillow's libwebp smooths the colors when it scales them up from the half resolution WebP stores them in; the decoder in Java doesn't, so the pixels differ a little.
