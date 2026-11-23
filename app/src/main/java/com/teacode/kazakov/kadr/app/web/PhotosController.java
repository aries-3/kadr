package com.teacode.kazakov.kadr.app.web;

import com.teacode.kazakov.kadr.app.api.PhotosApi;
import com.teacode.kazakov.kadr.core.photos.PhotoService;
import com.teacode.kazakov.kadr.core.photos.Thumbnail;
import com.teacode.kazakov.kadr.domain.files.PhotoFiles;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Photos in the catalog: original files and thumbnails.
 */
@RestController
class PhotosController implements PhotosApi {

    private final PhotoService photos;

    PhotosController(PhotoService photos) {
        this.photos = photos;
    }

    @Override
    public ResponseEntity<Resource> getPhotoFile(Long photoId) {
        Path file = photos.file(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(PhotoFiles.mediaType(file.getFileName().toString())))
                .body(new FileSystemResource(file));
    }

    /**
     * The browser keeps the thumbnail and asks each time whether it is still the same; Spring
     * answers {@code 304 Not Modified} when the {@code If-None-Match} header names this ETag.
     */
    @Override
    public ResponseEntity<Resource> getPhotoThumbnail(Long photoId) {
        Thumbnail thumbnail = photos.thumbnail(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .eTag(thumbnail.photo().hex())
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(thumbnail.file()));
    }
}
