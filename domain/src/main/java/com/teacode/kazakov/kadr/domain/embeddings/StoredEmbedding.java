package com.teacode.kazakov.kadr.domain.embeddings;

/**
 * A vector as written to the catalog.
 *
 * @param embedding the photo and its vector
 * @param seq       the write number: every write gets the next one
 */
public record StoredEmbedding(PhotoEmbedding embedding, long seq) {
}
