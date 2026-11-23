package com.teacode.kazakov.kadr.domain.catalog;

import java.nio.file.Path;
import java.util.List;

/**
 * A photo with the files it is stored in that are present on disk.
 *
 * @param id          catalog id of the photo
 * @param contentHash identity hash of its content
 * @param files       absolute paths of its present files, in path order; empty if all are missing
 */
public record Photo(long id, ContentHash contentHash, List<Path> files) {
}
