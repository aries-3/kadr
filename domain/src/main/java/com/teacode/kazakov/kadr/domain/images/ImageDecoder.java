package com.teacode.kazakov.kadr.domain.images;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Decodes photos into pixels for the embedding model.
 */
public interface ImageDecoder {

    /**
     * Decodes an image file into upright 8-bit RGB: turned as its EXIF orientation says, any
     * transparency dropped. A large image is decoded at a reduced resolution, as far as its
     * shorter side stays at least {@code minSide}, so it takes less memory and time.
     *
     * @param file    absolute path of a JPEG, PNG or WebP file
     * @param minSide the shortest side worth keeping, in pixels
     * @return the pixels
     * @throws IOException               if the file can't be opened
     * @throws UndecodableImageException if the content isn't a supported image, is damaged or has
     *                                   too many pixels
     */
    RgbImage decode(Path file, int minSide) throws IOException, UndecodableImageException;
}
