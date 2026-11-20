package com.teacode.kazakov.kadr.domain.files;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Computes the identity hash of a file.
 */
public interface ContentHasher {

    /**
     * Hashes a file; copies of a file get equal hashes.
     *
     * @param file absolute path of a regular file
     * @return identity hash of the file's content
     * @throws IOException if the file can't be read, or gets shorter while being read
     */
    ContentHash hash(Path file) throws IOException;
}
