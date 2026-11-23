package com.teacode.kazakov.kadr.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The EXIF orientation tag: how an image is stored relative to upright. Cameras store a photo as
 * the sensor read it and record here how to turn it. The constants are in the order of the tag
 * values 1 to 8.
 */
enum Orientation {

    /** Stored upright. */
    NORMAL,
    /** Mirrored left to right. */
    MIRROR_HORIZONTAL,
    /** Upside down. */
    ROTATE_180,
    /** Mirrored top to bottom. */
    MIRROR_VERTICAL,
    /** Rows and columns swapped. */
    TRANSPOSE,
    /** Upright after turning 90° clockwise. */
    ROTATE_90,
    /** Rows and columns swapped across the other diagonal. */
    TRANSVERSE,
    /** Upright after turning 90° counterclockwise. */
    ROTATE_270;

    /**
     * Reads the orientation of an image file.
     *
     * @return the orientation; {@link #NORMAL} if the file has none or it can't be read
     */
    static Orientation of(Path file) {
        try {
            return of(ImageMetadataReader.readMetadata(file.toFile()));
        } catch (ImageProcessingException | IOException e) {
            // Without readable metadata the image is shown as stored
            return NORMAL;
        }
    }

    /**
     * Reads the orientation of an image held in memory.
     *
     * @return the orientation; {@link #NORMAL} if the image has none or it can't be read
     */
    static Orientation of(byte[] image) {
        try {
            return of(ImageMetadataReader.readMetadata(new ByteArrayInputStream(image), image.length));
        } catch (ImageProcessingException | IOException e) {
            return NORMAL;
        }
    }

    private static Orientation of(Metadata metadata) {
        ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        try {
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int value = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (value >= 1 && value <= values().length) {
                    return values()[value - 1];
                }
            }
        } catch (MetadataException e) {
            // A tag that isn't a number: shown as stored
        }
        return NORMAL;
    }

    /**
     * {@return whether the upright image is the stored one turned on its side: width and height swap}
     */
    boolean swapsSides() {
        return compareTo(TRANSPOSE) >= 0;
    }

    /**
     * The transform that turns a stored image upright.
     *
     * @param width  width of the stored image
     * @param height height of the stored image
     * @return the transform from the stored image to the upright one
     */
    AffineTransform upright(double width, double height) {
        // new AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00·x + m01·y + m02, y' = m10·x + m11·y + m12
        return switch (this) {
            case NORMAL -> new AffineTransform();
            case MIRROR_HORIZONTAL -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case ROTATE_180 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case MIRROR_VERTICAL -> new AffineTransform(1, 0, 0, -1, 0, height);
            case TRANSPOSE -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case ROTATE_90 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case TRANSVERSE -> new AffineTransform(0, -1, -1, 0, height, width);
            case ROTATE_270 -> new AffineTransform(0, -1, 1, 0, 0, width);
        };
    }

    /**
     * Turns a stored image upright, moving the pixels as they are.
     *
     * @param image the stored image
     * @return the upright image; the same one if it is stored upright
     */
    RgbImage upright(RgbImage image) {
        if (this == NORMAL) {
            return image;
        }
        int width = image.width();
        int height = image.height();
        int last = (height - 1) * width;
        // Where the pixel (x, y) of the upright image is in the stored one:
        // start + x × xStep + y × yStep, as an index of a pixel
        int[] walk = switch (this) {
            case NORMAL -> new int[] {0, 1, width};
            case MIRROR_HORIZONTAL -> new int[] {width - 1, -1, width};
            case ROTATE_180 -> new int[] {last + width - 1, -1, -width};
            case MIRROR_VERTICAL -> new int[] {last, 1, -width};
            case TRANSPOSE -> new int[] {0, width, 1};
            case ROTATE_90 -> new int[] {last, -width, 1};
            case TRANSVERSE -> new int[] {last + width - 1, -width, -1};
            case ROTATE_270 -> new int[] {width - 1, width, -1};
        };
        int uprightWidth = swapsSides() ? height : width;
        int uprightHeight = swapsSides() ? width : height;
        byte[] stored = image.pixels();
        byte[] upright = new byte[stored.length];
        int i = 0;
        for (int y = 0; y < uprightHeight; y++) {
            int from = walk[0] + y * walk[2];
            for (int x = 0; x < uprightWidth; x++, from += walk[1]) {
                upright[i++] = stored[from * 3];
                upright[i++] = stored[from * 3 + 1];
                upright[i++] = stored[from * 3 + 2];
            }
        }
        return new RgbImage(uprightWidth, uprightHeight, upright);
    }
}
