package com.teacode.kazakov.kadr.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.teacode.kazakov.kadr.domain.files.FileEntry;
import com.teacode.kazakov.kadr.domain.files.FolderUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileTreeTest {

    private final LocalFileTree tree = new LocalFileTree();

    @TempDir
    Path folder;

    @Test
    void walksPhotosAtAnyDepth() throws IOException {
        Path a = file("a.jpg");
        Path b = file("2023/07/b.JPEG");
        Path c = file("2023/c.png");
        Path d = file("d.webp");

        assertThat(walk()).extracting(FileEntry::path).containsExactlyInAnyOrder(a, b, c, d);
    }

    @Test
    void skipsOtherFilesHiddenAndServiceFolders() throws IOException {
        Path photo = file("a.jpg");
        file("notes.txt");
        file("b.gif");
        file(".c.jpg");
        file(".thumbnails/d.jpg");
        file("@eaDir/e.jpg");
        file("2023/#recycle/f.jpg");

        assertThat(walk()).extracting(FileEntry::path).containsExactly(photo);
    }

    @Test
    void doesNotFollowSymbolicLinks() throws IOException {
        Path photo = file("real/a.jpg");
        Files.createSymbolicLink(folder.resolve("link.jpg"), photo);
        Files.createSymbolicLink(folder.resolve("linked-folder"), photo.getParent());

        assertThat(walk()).extracting(FileEntry::path).containsExactly(photo);
    }

    @Test
    void reportsSizeAndModificationTime() throws IOException {
        Path photo = file("a.jpg");
        Files.write(photo, new byte[123]);
        Files.setLastModifiedTime(photo, FileTime.fromMillis(1_700_000_000_123L));

        assertThat(walk()).containsExactly(new FileEntry(photo, 123, 1_700_000_000_123L));
    }

    @Test
    void stopsOnceStopIsRequested() throws IOException {
        file("a.jpg");
        file("b.jpg");
        file("c.jpg");
        List<FileEntry> visited = new ArrayList<>();

        tree.walk(folder, () -> !visited.isEmpty(), visited::add);

        assertThat(visited).hasSize(1);
    }

    @Test
    void pollsTheStopSignalWithoutPhotos() throws IOException {
        file("notes.txt");
        file("raw/a.cr2");
        file(".thumbnails/b.jpg");
        AtomicInteger polls = new AtomicInteger();

        tree.walk(folder, () -> polls.incrementAndGet() > 2, file -> {
        });

        // The folder, then one entry of it, then the stop: no photo is needed to stop
        assertThat(polls).hasValue(3);
    }

    @Test
    void skipsAnUnreadableSubfolder() throws IOException {
        Path photo = file("a.jpg");
        file("locked/b.jpg");
        Path locked = folder.resolve("locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "running as root: permissions aren't enforced");

            assertThat(walk()).extracting(FileEntry::path).containsExactly(photo);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void realFolderResolvesLinks() throws IOException, FolderUnavailableException {
        Path real = Files.createDirectory(folder.resolve("real"));
        Path link = Files.createSymbolicLink(folder.resolve("link"), real);

        assertThat(tree.realFolder(link)).isEqualTo(real.toRealPath());
        assertThat(tree.realFolder(folder.resolve("real/../real"))).isEqualTo(real.toRealPath());
    }

    @Test
    void realFolderRejectsWhatCantBeWalked() throws IOException {
        Path file = file("a.jpg");

        assertThatExceptionOfType(FolderUnavailableException.class)
                .isThrownBy(() -> tree.realFolder(folder.resolve("absent")))
                .withMessageContaining("doesn't exist");
        assertThatExceptionOfType(FolderUnavailableException.class)
                .isThrownBy(() -> tree.realFolder(file))
                .withMessageContaining("Not a folder");
    }

    @Test
    void realFolderRejectsAnUnreadableFolder() throws IOException {
        Path locked = Files.createDirectory(folder.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "running as root: permissions aren't enforced");

            assertThatExceptionOfType(FolderUnavailableException.class)
                    .isThrownBy(() -> tree.realFolder(locked))
                    .withMessageContaining("can't be read");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private List<FileEntry> walk() {
        List<FileEntry> files = new ArrayList<>();
        tree.walk(folder, () -> false, files::add);
        return files;
    }

    private Path file(String relative) throws IOException {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, relative.getBytes());
    }
}
