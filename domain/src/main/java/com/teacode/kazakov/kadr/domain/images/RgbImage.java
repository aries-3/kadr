package com.teacode.kazakov.kadr.domain.images;

/**
 * A decoded image: 8-bit RGB pixels, row by row from the top, three bytes a pixel.
 * <p>
 * The pixel array isn't copied: whoever makes an image hands it over and doesn't change it.
 *
 * @param width  in pixels, at least 1
 * @param height in pixels, at least 1
 * @param pixels {@code width × height × 3} bytes: red, green and blue of each pixel, unsigned
 */
public record RgbImage(int width, int height, byte[] pixels) {

    /**
     * @param width  in pixels, at least 1
     * @param height in pixels, at least 1
     * @param pixels {@code width × height × 3} bytes: red, green and blue of each pixel, unsigned
     * @throws IllegalArgumentException if a side is less than 1 or the array is of another length
     */
    public RgbImage {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("An image of %d × %d pixels".formatted(width, height));
        }
        if (pixels.length != (long) width * height * 3) {
            throw new IllegalArgumentException("%d bytes for %d × %d RGB pixels".formatted(pixels.length, width, height));
        }
    }
}
