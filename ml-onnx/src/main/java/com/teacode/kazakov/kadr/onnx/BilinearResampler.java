package com.teacode.kazakov.kadr.onnx;

import com.teacode.kazakov.kadr.domain.images.RgbImage;

/**
 * Resizes images exactly as Pillow's {@code Image.resize} with the bilinear filter does
 * ({@code Resample.c}), as the Hugging Face image processors use it: a pixel of the result is the
 * weighted mean of the source pixels under a triangle that widens with the scale. So shrinking
 * averages the source instead of skipping pixels, unlike a plain bilinear lookup of the four
 * nearest ones.
 * <p>
 * Same steps as Pillow, down to the rounding: the weights are fixed-point integers; a horizontal
 * pass makes an intermediate 8-bit image of the rows the vertical pass needs, which makes the
 * result.
 */
final class BilinearResampler {

    /** Bits of the fractional part of the fixed-point weights. */
    private static final int PRECISION_BITS = 32 - 8 - 2;

    private BilinearResampler() {
    }

    /**
     * Resizes an image, ignoring its aspect ratio.
     *
     * @param image  the source
     * @param width  of the result
     * @param height of the result
     * @return the resized image; the same one if it is of that size already
     */
    static RgbImage resize(RgbImage image, int width, int height) {
        RgbImage result = image;
        Weights vertical = new Weights(image.height(), height);
        if (width != image.width()) {
            // Only the rows the vertical pass reads
            int first = vertical.start[0];
            int last = vertical.start[height - 1] + vertical.count[height - 1];
            result = horizontal(image, new Weights(image.width(), width), first, last);
            vertical.shift(-first);
        }
        if (height != image.height()) {
            result = vertical(result, vertical);
        }
        return result;
    }

    private static RgbImage horizontal(RgbImage image, Weights weights, int firstRow, int lastRow) {
        int sourceWidth = image.width();
        int width = weights.start.length;
        int height = lastRow - firstRow;
        byte[] source = image.pixels();
        byte[] target = new byte[width * height * 3];
        int t = 0;
        for (int y = 0; y < height; y++) {
            int row = (y + firstRow) * sourceWidth;
            for (int x = 0; x < width; x++) {
                int from = (row + weights.start[x]) * 3;
                int k = x * weights.size;
                int red = 1 << (PRECISION_BITS - 1);
                int green = red;
                int blue = red;
                for (int i = 0; i < weights.count[x]; i++, from += 3) {
                    int weight = weights.values[k + i];
                    red += (source[from] & 0xFF) * weight;
                    green += (source[from + 1] & 0xFF) * weight;
                    blue += (source[from + 2] & 0xFF) * weight;
                }
                target[t++] = clip8(red);
                target[t++] = clip8(green);
                target[t++] = clip8(blue);
            }
        }
        return new RgbImage(width, height, target);
    }

    private static RgbImage vertical(RgbImage image, Weights weights) {
        int width = image.width();
        int height = weights.start.length;
        int stride = width * 3;
        byte[] source = image.pixels();
        byte[] target = new byte[width * height * 3];
        int t = 0;
        for (int y = 0; y < height; y++) {
            int k = y * weights.size;
            for (int x = 0; x < width; x++) {
                int from = weights.start[y] * stride + x * 3;
                int red = 1 << (PRECISION_BITS - 1);
                int green = red;
                int blue = red;
                for (int i = 0; i < weights.count[y]; i++, from += stride) {
                    int weight = weights.values[k + i];
                    red += (source[from] & 0xFF) * weight;
                    green += (source[from + 1] & 0xFF) * weight;
                    blue += (source[from + 2] & 0xFF) * weight;
                }
                target[t++] = clip8(red);
                target[t++] = clip8(green);
                target[t++] = clip8(blue);
            }
        }
        return new RgbImage(width, height, target);
    }

    private static byte clip8(int value) {
        int shifted = value >> PRECISION_BITS;
        return (byte) Math.clamp(shifted, 0, 255);
    }

    /**
     * Which source pixels make each pixel of the result along one axis, and with what weights:
     * Pillow's {@code precompute_coeffs} and {@code normalize_coeffs_8bpc}.
     */
    private static final class Weights {

        /** The first source pixel of each target pixel. */
        final int[] start;
        /** How many source pixels from the first. */
        final int[] count;
        /** {@link #size} weights for each target pixel, fixed-point. */
        final int[] values;
        /** The most source pixels a target pixel takes. */
        final int size;

        Weights(int sourceSize, int targetSize) {
            double scale = (double) sourceSize / targetSize;
            // Shrinking widens the triangle, so that every source pixel counts
            double filterScale = Math.max(scale, 1.0);
            double support = filterScale;
            // Multiplied by, not divided by filterScale, as Pillow does: the last bit may differ
            double inverseScale = 1.0 / filterScale;
            size = (int) Math.ceil(support) * 2 + 1;
            start = new int[targetSize];
            count = new int[targetSize];
            values = new int[targetSize * size];
            double[] weights = new double[size];
            for (int x = 0; x < targetSize; x++) {
                double center = (x + 0.5) * scale;
                int min = Math.max(0, (int) (center - support + 0.5));
                int max = Math.min(sourceSize, (int) (center + support + 0.5)) - min;
                double sum = 0;
                for (int i = 0; i < max; i++) {
                    weights[i] = triangle((i + min - center + 0.5) * inverseScale);
                    sum += weights[i];
                }
                for (int i = 0; i < max; i++) {
                    double weight = sum != 0 ? weights[i] / sum : weights[i];
                    values[x * size + i] = weight < 0
                            ? (int) (-0.5 + weight * (1 << PRECISION_BITS))
                            : (int) (0.5 + weight * (1 << PRECISION_BITS));
                }
                start[x] = min;
                count[x] = max;
            }
        }

        void shift(int offset) {
            for (int i = 0; i < start.length; i++) {
                start[i] += offset;
            }
        }

        private static double triangle(double x) {
            double distance = Math.abs(x);
            return distance < 1.0 ? 1.0 - distance : 0.0;
        }
    }
}
