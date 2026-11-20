package com.teacode.kazakov.kadr.domain.files;

import java.nio.file.Path;

/**
 * A photo file found by a walk.
 *
 * @param path      absolute path
 * @param sizeBytes size in bytes
 * @param mtimeMs   last-modified time in milliseconds since the epoch
 */
public record FileEntry(Path path, long sizeBytes, long mtimeMs) {
}
