package com.teacode.kazakov.kadr.domain.images;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Makes thumbnails: small previews of photos for showing many at once.
 */
public interface ThumbnailRenderer {

    /**
     * Renders a thumbnail of an image file: the image turned upright, as its EXIF orientation
     * says, and scaled down to fit in a square of the renderer's size.
     *
     * @param file absolute path of a JPEG, PNG or WebP file
     * @return the thumbnail as JPEG
     * @throws IOException               if the file can't be opened
     * @throws UndecodableImageException if the content isn't a supported image, is damaged or has
     *                                   too many pixels
     */
    byte[] render(Path file) throws IOException, UndecodableImageException;
}
