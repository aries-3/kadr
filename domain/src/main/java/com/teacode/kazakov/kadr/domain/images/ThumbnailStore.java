package com.teacode.kazakov.kadr.domain.images;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Thumbnails kept between requests, by the content hash of their photo: copies of a photo share
 * one thumbnail, and a thumbnail never goes stale, since other content is another photo.
 */
public interface ThumbnailStore {

    /**
     * Finds a stored thumbnail.
     *
     * @param photo content hash of the photo
     * @return the thumbnail file, or empty if none is stored
     */
    Optional<Path> find(ContentHash photo);

    /**
     * Stores a thumbnail, replacing a stored one. A reader never sees a partly written file.
     *
     * @param photo content hash of the photo
     * @param jpeg  the thumbnail
     * @return the stored thumbnail file
     * @throws IOException if the thumbnail can't be written
     */
    Path save(ContentHash photo, byte[] jpeg) throws IOException;
}
