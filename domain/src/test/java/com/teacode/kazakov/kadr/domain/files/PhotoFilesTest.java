package com.teacode.kazakov.kadr.domain.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhotoFilesTest {

    @ParameterizedTest
    @ValueSource(strings = {"a.jpg", "B.JPG", "c.Jpeg", "d.png", "e.webp", "IMG_2023.07.15.jpg"})
    void photos(String name) {
        assertThat(PhotoFiles.isPhoto(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {".a.jpg", "._IMG_0001.jpg", "a.gif", "a.heic", "a.cr2", "jpg", "a.jpg.txt", "a."})
    void notPhotos(String name) {
        assertThat(PhotoFiles.isPhoto(name)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"a.jpg, image/jpeg", "B.JPEG, image/jpeg", "c.png, image/png", "d.WebP, image/webp"})
    void mediaTypes(String name, String type) {
        assertThat(PhotoFiles.mediaType(name)).isEqualTo(type);
    }

    @Test
    void notAPhotoHasNoMediaType() {
        assertThatIllegalArgumentException().isThrownBy(() -> PhotoFiles.mediaType("a.gif"));
    }

    @ParameterizedTest
    @ValueSource(strings = {".git", ".thumbnails", "@eaDir", "#recycle", "$RECYCLE.BIN", "System Volume Information"})
    void skippedFolders(String name) {
        assertThat(PhotoFiles.isSkippedFolder(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2023", "eaDir", "recycle", "Photos"})
    void walkedFolders(String name) {
        assertThat(PhotoFiles.isSkippedFolder(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p/a.jpg", "/p/2023/07/a.jpg"})
    void reachable(String file) {
        assertThat(PhotoFiles.isReachable(Path.of("/p"), Path.of(file))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p/.private/a.jpg", "/p/2023/@eaDir/a.jpg", "/p/.a.jpg", "/p/a.gif", "/other/a.jpg", "/p"})
    void unreachable(String file) {
        assertThat(PhotoFiles.isReachable(Path.of("/p"), Path.of(file))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p/.private/a.jpg", "/p/.private/2023/a.jpg"})
    void aHiddenFolderReachesItsOwnFiles(String file) {
        assertThat(PhotoFiles.isReachable(Path.of("/p/.private"), Path.of(file))).isTrue();
    }
}
