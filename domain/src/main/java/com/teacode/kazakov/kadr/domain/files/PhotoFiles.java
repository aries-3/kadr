package com.teacode.kazakov.kadr.domain.files;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which files a scan takes as photos, and which folders it skips.
 */
public final class PhotoFiles {

    /** Media types of the photo files by extension, lowercase. */
    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

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
        return !isHidden(fileName) && MEDIA_TYPES.containsKey(extension(fileName));
    }

    /**
     * The media type of a photo file, by its extension.
     *
     * @param fileName name of the file, without its folder
     * @return {@code image/jpeg}, {@code image/png} or {@code image/webp}
     * @throws IllegalArgumentException if the extension isn't one of a photo
     */
    public static String mediaType(String fileName) {
        String type = MEDIA_TYPES.get(extension(fileName));
        if (type == null) {
            throw new IllegalArgumentException("Not a photo file: " + fileName);
        }
        return type;
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

    /** Lowercase extension; empty if there is none, or the name only starts with a dot. */
    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
