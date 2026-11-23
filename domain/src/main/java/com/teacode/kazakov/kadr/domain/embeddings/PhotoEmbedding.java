package com.teacode.kazakov.kadr.domain.embeddings;

/**
 * The vector of a photo.
 * <p>
 * The array isn't copied: whoever makes one hands it over and doesn't change it.
 *
 * @param photoId the photo
 * @param vector  its unit vector
 */
public record PhotoEmbedding(long photoId, float[] vector) {
}
