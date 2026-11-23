package com.teacode.kazakov.kadr.imaging;

import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Decodes photos for the embedding model with ImageIO: JPEG and WebP by the TwelveMonkeys plugins,
 * PNG by the JDK; the format is recognized by the content, not by the file name.
 * <p>
 * The pixels are made 8-bit RGB the way Pillow's {@code convert("RGB")} does: the model was
 * checked against a reference in Python that reads images with Pillow. So samples are taken as
 * stored, without color management; transparency is dropped; 16-bit samples keep their high byte.
 */
public final class ImageIoDecoder implements ImageDecoder {

    /** Images with more pixels aren't decoded: about a 10000 × 10000 photo. */
    public static final long DEFAULT_MAX_PIXELS = 100_000_000L;

    private final long maxPixels;

    /**
     * @param maxPixels images with more pixels aren't decoded
     */
    public ImageIoDecoder(long maxPixels) {
        this.maxPixels = maxPixels;
        ImageReading.registerPlugins();
    }

    @Override
    public RgbImage decode(Path file, int minSide) throws IOException, UndecodableImageException {
        if (minSide < 1) {
            throw new IllegalArgumentException("The shortest side to keep is " + minSide);
        }
        BufferedImage image = ImageReading.read(file, maxPixels, (width, height) ->
                Math.max(1, Math.min(width, height) / minSide));
        return Orientation.of(file).upright(RgbPixels.of(image));
    }
}
