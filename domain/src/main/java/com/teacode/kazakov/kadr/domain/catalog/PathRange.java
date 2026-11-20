package com.teacode.kazakov.kadr.domain.catalog;

import java.nio.file.Path;

/**
 * Paths inside a folder as a half-open range of strings: {@code from <= path < to}.
 * <p>
 * A range query uses the index on the path, and needs no {@code LIKE}, where {@code _} in file
 * names would be a wildcard. Comparison is by UTF-16 code units, as {@link String#compareTo}.
 *
 * @param from inclusive lower bound: the folder path with a trailing separator
 * @param to   exclusive upper bound: {@code from} with the trailing separator replaced by the next
 *             character ({@code '/' + 1 = '0'})
 */
public record PathRange(String from, String to) {

    /**
     * Builds the range of paths inside a folder.
     *
     * @param folder absolute path of the folder
     * @return the range of paths inside the folder, without the folder itself
     */
    public static PathRange under(Path folder) {
        String separator = folder.getFileSystem().getSeparator();
        String path = folder.toString();
        String prefix = path.endsWith(separator) ? path : path + separator;
        int last = prefix.length() - 1;
        return new PathRange(prefix, prefix.substring(0, last) + (char) (prefix.charAt(last) + 1));
    }

    /**
     * Checks whether a path is inside the range.
     *
     * @param path absolute path
     * @return {@code true} if {@code from <= path < to}
     */
    public boolean contains(String path) {
        return path.compareTo(from) >= 0 && path.compareTo(to) < 0;
    }
}
