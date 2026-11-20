package com.teacode.kazakov.kadr.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathRangeTest {

    private final PathRange photos = PathRange.under(Path.of("/home/user/photos"));

    @Test
    void rangeStartsAfterTheSeparator() {
        assertThat(photos).isEqualTo(new PathRange("/home/user/photos/", "/home/user/photos0"));
    }

    @Test
    void containsFilesAtAnyDepth() {
        assertThat(photos.contains("/home/user/photos/a.jpg")).isTrue();
        assertThat(photos.contains("/home/user/photos/2023/07/IMG_0001.jpg")).isTrue();
    }

    @Test
    void excludesSiblingFoldersWithTheSamePrefix() {
        assertThat(photos.contains("/home/user/photos-old/a.jpg")).isFalse();
        assertThat(photos.contains("/home/user/photos2/a.jpg")).isFalse();
        assertThat(photos.contains("/home/user/photos_backup/a.jpg")).isFalse();
        assertThat(photos.contains("/home/user/photos.jpg")).isFalse();
    }

    @Test
    void excludesTheFolderItself() {
        assertThat(photos.contains("/home/user/photos")).isFalse();
    }

    @Test
    void fileSystemRootContainsEverything() {
        PathRange root = PathRange.under(Path.of("/"));

        assertThat(root).isEqualTo(new PathRange("/", "0"));
        assertThat(root.contains("/a.jpg")).isTrue();
    }
}
