package com.teacode.kazakov.kadr.domain.files;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Which files a scan takes as photos, and which folders it skips.
 */
public final class PhotoFiles {

    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** Service folders of NAS systems and Windows, compared ignoring case. */
    private static final Set<String> SERVICE_FOLDERS = Set.of(
            "@eadir", "#recycle", "$recycle.bin", "system volume information");

    private PhotoFiles() {
    }

    /**
     * A photo is a file with a supported extension, ignoring case; hidden files aren't photos.
     *
     * @param fileName name of the file, without its folder
     * @return {@code true} if the file is a photo
     */
    public static boolean isPhoto(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return !isHidden(fileName)
                && dot > 0
                && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Hidden and service folders aren't walked.
     *
     * @param folderName name of the folder, without its parent
     * @return {@code true} if a walk doesn't enter the folder
     */
    public static boolean isSkippedFolder(String folderName) {
        return isHidden(folderName) || SERVICE_FOLDERS.contains(folderName.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a walk of {@code folder} reaches {@code file}: no skipped folder between them, and
     * the file is a photo. A file the walk doesn't reach can't be declared missing by it.
     *
     * @param folder absolute path of the walked folder
     * @param file   absolute path of the file
     * @return {@code true} if the walk reaches the file
     */
    public static boolean isReachable(Path folder, Path file) {
        if (!file.startsWith(folder) || file.equals(folder)) {
            return false;
        }
        Path relative = folder.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (isSkippedFolder(relative.getName(i).toString())) {
                return false;
            }
        }
        return isPhoto(relative.getFileName().toString());
    }

    private static boolean isHidden(String name) {
        return name.startsWith(".");
    }
}
