package com.teacode.kazakov.kadr.core.photos;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import java.nio.file.Path;

/**
 * A thumbnail ready to serve.
 *
 * @param file  the JPEG file
 * @param photo content hash of the photo: the thumbnail changes only with it
 */
public record Thumbnail(Path file, ContentHash photo) {
}
