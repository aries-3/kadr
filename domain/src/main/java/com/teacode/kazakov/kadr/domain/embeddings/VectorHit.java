package com.teacode.kazakov.kadr.domain.embeddings;

/**
 * A photo found by its vector.
 *
 * @param photoId the photo
 * @param score   the cosine similarity of its vector to the query, from −1 to 1
 */
public record VectorHit(long photoId, double score) {
}
