package com.teacode.kazakov.kadr.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.geom.AffineTransform;
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
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int value = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (value >= 1 && value <= values().length) {
                    return values()[value - 1];
                }
            }
        } catch (ImageProcessingException | MetadataException | IOException e) {
            // Without readable metadata the image is shown as stored
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
}
