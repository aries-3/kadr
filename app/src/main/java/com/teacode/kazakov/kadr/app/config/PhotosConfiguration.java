package com.teacode.kazakov.kadr.app.config;

import com.teacode.kazakov.kadr.core.photos.PhotoService;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.images.ThumbnailRenderer;
import com.teacode.kazakov.kadr.domain.images.ThumbnailStore;
import com.teacode.kazakov.kadr.fs.DiskThumbnailStore;
import com.teacode.kazakov.kadr.imaging.JpegThumbnailRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serving photos: their original files and thumbnails.
 */
@Configuration(proxyBeanMethods = false)
class PhotosConfiguration {

    /** Longest side of a thumbnail, in pixels; the API contract states it. */
    static final int THUMBNAIL_SIZE = 512;

    @Bean
    ThumbnailRenderer thumbnailRenderer() {
        return new JpegThumbnailRenderer(THUMBNAIL_SIZE, JpegThumbnailRenderer.DEFAULT_MAX_PIXELS);
    }

    /**
     * Thumbnails of each size have their own folder: a new size doesn't serve the old thumbnails.
     */
    @Bean
    ThumbnailStore thumbnailStore(KadrProperties properties) {
        return new DiskThumbnailStore(properties.dataDir().resolve("thumbnails").resolve(String.valueOf(THUMBNAIL_SIZE)));
    }

    @Bean
    PhotoService photoService(
            PhotoRepository photos, FileTree fileTree, ThumbnailRenderer renderer, ThumbnailStore thumbnails) {
        return new PhotoService(photos, fileTree, renderer, thumbnails);
    }
}
