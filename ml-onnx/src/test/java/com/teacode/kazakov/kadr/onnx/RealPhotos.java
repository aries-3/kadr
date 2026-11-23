package com.teacode.kazakov.kadr.onnx;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.teacode.kazakov.kadr.domain.files.PhotoFiles;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Photos of the developer's own, for the measurements that need real ones: a folder given in a
 * system property. They never go into the repository.
 */
final class RealPhotos {

    private RealPhotos() {
    }

    /**
     * The folder in a system property; the test is skipped without it.
     */
    static Path folder(String property) {
        String folder = System.getProperty(property);
        assumeTrue(folder != null && !folder.isBlank(), () -> "Set " + property + " to a folder of photos to run this test");
        Path path = Path.of(folder);
        assumeTrue(Files.isDirectory(path), () -> "Not a folder: " + path);
        return path;
    }

    /**
     * Photos in a folder and its subfolders, as a scan would find them, in the order of their paths.
     *
     * @param limit at most this many
     */
    static List<Path> list(Path folder, int limit) {
        List<Path> photos = new ArrayList<>();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String name = dir.getFileName().toString();
                    return !dir.equals(folder) && (name.startsWith(".") || PhotoFiles.isSkippedFolder(name))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    if (attributes.isRegularFile() && !name.startsWith(".") && PhotoFiles.isPhoto(name)) {
                        photos.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return photos.stream().sorted().limit(limit).toList();
    }

    /**
     * Decodes a photo for the model; one that can't be decoded is left out, with a note.
     */
    static List<RgbImage> decode(ImageIoDecoder decoder, Path photo, int minSide) {
        try {
            return List.of(decoder.decode(photo, minSide));
        } catch (IOException | UndecodableImageException e) {
            System.out.println("  left out: " + e.getMessage());
            return List.of();
        }
    }
}
