package com.teacode.kazakov.kadr.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskThumbnailStoreTest {

    private static final ContentHash PHOTO = new ContentHash("ab" + "0".repeat(62));

    @TempDir
    Path folder;

    @Test
    void nothingIsStoredAtFirst() {
        assertThat(new DiskThumbnailStore(folder.resolve("thumbnails")).find(PHOTO)).isEmpty();
    }

    @Test
    void savedThumbnailIsFound() throws IOException {
        DiskThumbnailStore store = new DiskThumbnailStore(folder.resolve("thumbnails"));

        Path saved = store.save(PHOTO, new byte[] {1, 2, 3});

        assertThat(saved).isEqualTo(folder.resolve("thumbnails/ab/" + PHOTO.hex() + ".jpg"));
        assertThat(saved).hasBinaryContent(new byte[] {1, 2, 3});
        assertThat(store.find(PHOTO)).contains(saved);
        try (Stream<Path> files = Files.list(saved.getParent())) {
            assertThat(files).as("no temporary files left").containsExactly(saved);
        }
    }

    @Test
    void savingAgainReplacesTheThumbnail() throws IOException {
        DiskThumbnailStore store = new DiskThumbnailStore(folder);
        store.save(PHOTO, new byte[] {1});

        Path saved = store.save(PHOTO, new byte[] {2});

        assertThat(saved).hasBinaryContent(new byte[] {2});
    }
}
