package com.teacode.kazakov.kadr.domain.catalog;

/**
 * A new or changed file found by a scan.
 *
 * @param path      absolute path
 * @param sizeBytes size in bytes
 * @param mtimeMs   last-modified time in milliseconds since the epoch
 * @param hash      identity hash of the content; files with equal hashes are one photo
 */
public record ScannedFile(String path, long sizeBytes, long mtimeMs, ContentHash hash) {
}
