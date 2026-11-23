package com.teacode.kazakov.kadr.imaging;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;

/**
 * Turns decoded images into 8-bit RGB the way Pillow's {@code convert("RGB")} does, as the
 * embedding model was checked against Pillow:
 * <ul>
 *   <li>samples are taken as stored, without color management: a grayscale image isn't
 *       brightened by a gamma conversion, an embedded color profile is ignored;</li>
 *   <li>transparency is dropped, transparent pixels keep their stored color;</li>
 *   <li>16-bit samples keep their high byte; samples of 1, 2 or 4 bits are stretched to 0–255;</li>
 *   <li>a palette image takes the colors of its palette.</li>
 * </ul>
 * CMYK JPEG arrives already converted to RGB by the TwelveMonkeys reader; without an embedded
 * profile its result is within one level of Pillow's.
 */
final class RgbPixels {

    private RgbPixels() {
    }

    /**
     * Converts an image.
     *
     * @param image a decoded image
     * @return its pixels as 8-bit RGB
     */
    static RgbImage of(BufferedImage image) {
        if (image.isAlphaPremultiplied()) {
            image.coerceData(false);
        }
        ColorModel model = image.getColorModel();
        int colorSpace = model.getColorSpace().getType();
        if (model instanceof IndexColorModel palette) {
            return fromPalette(image, palette);
        }
        if (colorSpace == ColorSpace.TYPE_RGB && model.getNumColorComponents() == 3) {
            return fromBands(image, new int[] {0, 1, 2});
        }
        if (colorSpace == ColorSpace.TYPE_GRAY && model.getNumColorComponents() == 1) {
            return fromBands(image, new int[] {0, 0, 0});
        }
        return fromRgb(image);
    }

    private static RgbImage fromPalette(BufferedImage image, IndexColorModel palette) {
        int width = image.getWidth();
        int height = image.getHeight();
        WritableRaster raster = image.getRaster();
        byte[] pixels = new byte[width * height * 3];
        int[] row = new int[width];
        int i = 0;
        for (int y = 0; y < height; y++) {
            raster.getSamples(0, y, width, 1, 0, row);
            for (int x = 0; x < width; x++) {
                pixels[i++] = (byte) palette.getRed(row[x]);
                pixels[i++] = (byte) palette.getGreen(row[x]);
                pixels[i++] = (byte) palette.getBlue(row[x]);
            }
        }
        return new RgbImage(width, height, pixels);
    }

    /**
     * Takes the red, green and blue of each pixel from the given bands of the raster.
     */
    private static RgbImage fromBands(BufferedImage image, int[] bands) {
        int width = image.getWidth();
        int height = image.getHeight();
        WritableRaster raster = image.getRaster();
        ColorModel model = image.getColorModel();
        byte[] pixels = new byte[width * height * 3];
        int[] row = new int[width];
        for (int channel = 0; channel < 3; channel++) {
            int band = bands[channel];
            int bits = model.getComponentSize(band);
            for (int y = 0; y < height; y++) {
                raster.getSamples(0, y, width, 1, band, row);
                int i = y * width * 3 + channel;
                for (int x = 0; x < width; x++, i += 3) {
                    pixels[i] = (byte) to8Bits(row[x], bits);
                }
            }
        }
        return new RgbImage(width, height, pixels);
    }

    /**
     * Any other color space, converted to sRGB by Java.
     */
    private static RgbImage fromRgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] pixels = new byte[width * height * 3];
        int[] row = new int[width];
        int i = 0;
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                pixels[i++] = (byte) (row[x] >> 16);
                pixels[i++] = (byte) (row[x] >> 8);
                pixels[i++] = (byte) row[x];
            }
        }
        return new RgbImage(width, height, pixels);
    }

    private static int to8Bits(int sample, int bits) {
        if (bits == 8) {
            return sample;
        }
        if (bits > 8) {
            return sample >>> (bits - 8);
        }
        return sample * 255 / ((1 << bits) - 1);
    }
}
