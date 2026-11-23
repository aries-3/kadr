package com.teacode.kazakov.kadr.core.photos;

import com.teacode.kazakov.kadr.domain.catalog.Photo;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.images.ThumbnailRenderer;
import com.teacode.kazakov.kadr.domain.images.ThumbnailStore;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves photos of the catalog: their original files and thumbnails.
 * <p>
 * Only files the catalog knows are served, and only those the last scan found. A file may still
 * be gone since then, so each one is checked, and the next file of the photo is tried.
 */
public final class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final PhotoRepository photos;
    private final FileTree fileTree;
    private final ThumbnailRenderer renderer;
    private final ThumbnailStore thumbnails;

    public PhotoService(
            PhotoRepository photos, FileTree fileTree, ThumbnailRenderer renderer, ThumbnailStore thumbnails) {
        this.photos = photos;
        this.fileTree = fileTree;
        this.renderer = renderer;
        this.thumbnails = thumbnails;
    }

    /**
     * Finds a file of a photo to serve: the first readable one in path order.
     *
     * @throws PhotoNotFoundException if there is no such photo or none of its files can be read
     */
    public Path file(long photoId) {
        return find(photoId).files().stream()
                .filter(fileTree::isReadable)
                .findFirst()
                .orElseThrow(() -> noReadableFile(photoId));
    }

    /**
     * Gets the thumbnail of a photo; on the first request it is rendered from a file of the
     * photo and stored.
     *
     * @throws PhotoNotFoundException    if there is no such photo, or the thumbnail isn't stored
     *                                   and none of the photo's files can be read
     * @throws UndecodablePhotoException if the photo's file can't be decoded as an image
     */
    public Thumbnail thumbnail(long photoId) {
        Photo photo = find(photoId);
        Optional<Path> stored = thumbnails.find(photo.contentHash());
        if (stored.isPresent()) {
            return new Thumbnail(stored.get(), photo.contentHash());
        }
        for (Path file : photo.files()) {
            if (!fileTree.isReadable(file)) {
                continue;
            }
            byte[] jpeg;
            try {
                jpeg = renderer.render(file);
            } catch (IOException e) {
                log.warn("Can't read {}: {}", file, e.toString());
                continue;
            } catch (UndecodableImageException e) {
                // Files of a photo have the same content: another one won't decode either
                throw new UndecodablePhotoException(e.getMessage(), e);
            }
            try {
                return new Thumbnail(thumbnails.save(photo.contentHash(), jpeg), photo.contentHash());
            } catch (IOException e) {
                throw new UncheckedIOException("Can't store the thumbnail of photo " + photoId, e);
            }
        }
        throw noReadableFile(photoId);
    }

    private Photo find(long photoId) {
        return photos.findPhoto(photoId)
                .orElseThrow(() -> new PhotoNotFoundException("Photo " + photoId + " not found"));
    }

    private static PhotoNotFoundException noReadableFile(long photoId) {
        return new PhotoNotFoundException("No file of photo " + photoId + " can be read");
    }
}
