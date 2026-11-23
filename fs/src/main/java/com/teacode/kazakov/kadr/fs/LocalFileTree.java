package com.teacode.kazakov.kadr.fs;

import com.teacode.kazakov.kadr.domain.files.FileEntry;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.files.FolderUnavailableException;
import com.teacode.kazakov.kadr.domain.files.PhotoFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Photo folders on the local file system.
 */
public final class LocalFileTree implements FileTree {

    private static final Logger log = LoggerFactory.getLogger(LocalFileTree.class);

    @Override
    public Path realFolder(Path folder) throws FolderUnavailableException {
        if (!Files.exists(folder)) {
            throw new FolderUnavailableException("The folder doesn't exist: " + folder);
        }
        if (!Files.isDirectory(folder)) {
            throw new FolderUnavailableException("Not a folder: " + folder);
        }
        try {
            Path real = folder.toRealPath();
            // Listing is the reliable check: it needs both read and execute permissions
            Files.newDirectoryStream(real).close();
            return real;
        } catch (IOException e) {
            throw new FolderUnavailableException("The folder can't be read: " + folder, e);
        }
    }

    @Override
    public void walk(Path folder, BooleanSupplier stopRequested, Consumer<FileEntry> visitor) {
        try {
            // No FOLLOW_LINKS: a link is visited as a file that isn't regular, and skipped
            Files.walkFileTree(folder, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (stopRequested.getAsBoolean()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return !dir.equals(folder) && PhotoFiles.isSkippedFolder(name(dir))
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (stopRequested.getAsBoolean()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isRegularFile() && PhotoFiles.isPhoto(name(file))) {
                        visitor.accept(new FileEntry(
                                file, attributes.size(), attributes.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    log.warn("Skipped {}: {}", file, e.toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException e) {
                    if (e != null) {
                        log.warn("Folder {} not walked to the end: {}", dir, e.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Not expected: the visitor handles every failure itself
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean isReadable(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(file);
    }

    private static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
}
