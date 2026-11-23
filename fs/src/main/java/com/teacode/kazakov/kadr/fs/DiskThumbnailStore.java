package com.teacode.kazakov.kadr.fs;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.images.ThumbnailStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Thumbnails as files in a folder: {@code <folder>/<first two hex digits>/<hash>.jpg}. The
 * subfolders keep a folder from holding hundreds of thousands of files.
 */
public final class DiskThumbnailStore implements ThumbnailStore {

    private final Path folder;

    /**
     * @param folder where the thumbnails are kept; created on the first write
     */
    public DiskThumbnailStore(Path folder) {
        this.folder = folder;
    }

    @Override
    public Optional<Path> find(ContentHash photo) {
        Path file = file(photo);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    @Override
    public Path save(ContentHash photo, byte[] jpeg) throws IOException {
        Path file = file(photo);
        Path parent = Files.createDirectories(file.getParent());
        // Written aside and renamed: a reader sees the old file or the new one, never a part
        Path temp = Files.createTempFile(parent, photo.hex(), ".tmp");
        try {
            Files.write(temp, jpeg);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return file;
    }

    private Path file(ContentHash photo) {
        return folder.resolve(photo.hex().substring(0, 2)).resolve(photo.hex() + ".jpg");
    }
}
