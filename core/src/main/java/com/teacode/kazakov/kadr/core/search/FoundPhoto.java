package com.teacode.kazakov.kadr.core.search;

import java.nio.file.Path;
import java.util.List;

/**
 * A photo found by a search.
 *
 * @param photoId the photo
 * @param score   the cosine similarity of the photo to the query; only the order of the scores
 *                means something
 * @param files   its files present on disk, at least one
 */
public record FoundPhoto(long photoId, double score, List<Path> files) {
}
