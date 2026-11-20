package com.teacode.kazakov.kadr.domain.catalog;

/**
 * A file as the catalog knows it.
 *
 * @param id        catalog id of the file
 * @param path      absolute path
 * @param sizeBytes size in bytes when the file was last recorded
 * @param mtimeMs   last-modified time in milliseconds since the epoch, when the file was last
 *                  recorded; a scan doesn't read a file whose size and time are unchanged
 * @param missing   the last scan didn't find the file
 */
public record CatalogFile(long id, String path, long sizeBytes, long mtimeMs, boolean missing) {
}
