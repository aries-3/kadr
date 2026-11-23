"""Makes the test images of the reference check: small generated scenes in the formats and color
modes the Java decoder must read as Pillow does. Needs only Pillow; the images are committed, so
this runs again only to change them.

    python3 make_test_images.py <folder>
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

WIDTH, HEIGHT = 640, 427


def scene(width, height, seed):
    """A landscape: sky, sun, mountains, a meadow, a lake; noise for texture."""
    rng = random.Random(seed)
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    horizon = int(height * 0.6)
    for y in range(horizon):
        t = y / horizon
        draw.line([(0, y), (width, y)], fill=(int(70 + 120 * t), int(130 + 90 * t), int(220 + 30 * t)))
    for y in range(horizon, height):
        t = (y - horizon) / (height - horizon)
        draw.line([(0, y), (width, y)], fill=(int(60 - 20 * t), int(150 - 40 * t), int(50 - 10 * t)))
    sun = (int(width * 0.78), int(height * 0.2))
    radius = int(height * 0.08)
    draw.ellipse([sun[0] - radius, sun[1] - radius, sun[0] + radius, sun[1] + radius], fill=(255, 230, 120))
    for peak in range(4):
        x = rng.randint(0, width)
        top = rng.randint(int(height * 0.25), int(height * 0.45))
        half = rng.randint(width // 6, width // 3)
        grey = rng.randint(90, 130)
        draw.polygon([(x - half, horizon), (x, top), (x + half, horizon)], fill=(grey, grey, grey + 15))
        draw.polygon([(x - half // 5, top + (horizon - top) // 5), (x, top), (x + half // 5, top + (horizon - top) // 5)],
                     fill=(245, 245, 250))
    draw.ellipse([int(width * 0.1), int(height * 0.72), int(width * 0.55), int(height * 0.92)], fill=(40, 90, 170))
    for _ in range(width * height // 40):
        x, y = rng.randrange(width), rng.randrange(horizon, height)
        r, g, b = image.getpixel((x, y))
        shade = rng.randint(-25, 25)
        image.putpixel((x, y), (max(0, r + shade), max(0, min(255, g + shade)), max(0, b + shade)))
    return image.filter(ImageFilter.SMOOTH)


def main(folder):
    folder.mkdir(parents=True, exist_ok=True)
    landscape = scene(WIDTH, HEIGHT, 1)

    # Shrunk to the model size
    landscape.save(folder / "landscape.jpg", quality=90)

    # Stored on its side, with the EXIF tag that turns it upright: a portrait
    portrait = scene(360, 480, 2)
    exif = Image.Exif()
    exif[0x0112] = 6
    portrait.transpose(Image.Transpose.ROTATE_90).save(folder / "portrait-exif-6.jpg", quality=90, exif=exif)

    # Transparent areas keep a color of their own, which converting to RGB must keep too
    alpha = scene(300, 300, 3).convert("RGBA")
    pixels = alpha.load()
    for y in range(300):
        for x in range(300):
            if (x - 150) ** 2 + (y - 150) ** 2 > 140 ** 2:
                r, g, b, _ = pixels[x, y]
                pixels[x, y] = (r, g, b, 0)
            elif x < 60:
                r, g, b, _ = pixels[x, y]
                pixels[x, y] = (r, g, b, 128)
    alpha.save(folder / "alpha.png")

    scene(400, 300, 4).convert("L").save(folder / "grayscale.jpg", quality=90)
    scene(320, 240, 5).convert("CMYK").save(folder / "cmyk.jpg", quality=90)
    scene(400, 300, 6).save(folder / "lossy.webp", quality=80)

    # Enlarged to the model size
    scene(120, 90, 7).save(folder / "small.png")

    scene(320, 200, 8).quantize(colors=64).save(folder / "palette.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
