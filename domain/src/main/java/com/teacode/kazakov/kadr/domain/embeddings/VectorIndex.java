package com.teacode.kazakov.kadr.domain.embeddings;

import java.util.List;

/**
 * Finds the photos whose vectors are nearest to a given one, among the vectors of one model.
 * <p>
 * The index is made from the vectors in the catalog and follows them by their write numbers: it
 * knows the number of the last write it has on disk, so after a crash it takes again only what
 * came later. Lost or unusable, it is made again from the catalog, without the model.
 */
public interface VectorIndex {

    /**
     * {@return the write number of the last vector the index has on disk; 0 if it has none}
     */
    long durableSeq();

    /**
     * Adds vectors, replacing those of the same photos; they are found within about a second, and
     * kept on disk after the next {@link #commit()}.
     *
     * @param embeddings the vectors, in the order they were written
     * @param lastSeq    the write number of the last of them
     */
    void upsert(List<PhotoEmbedding> embeddings, long lastSeq);

    /**
     * Saves what was added to disk, with the write number of the last of it.
     */
    void commit();

    /**
     * Finds the nearest vectors. The search is approximate: a few of the true nearest may be
     * missing, the fewer the more are asked for.
     *
     * @param query a unit vector of the model
     * @param limit the most photos to return
     * @return the photos, nearest first
     */
    List<VectorHit> search(float[] query, int limit);

    /**
     * {@return how many photos the index has}
     */
    int size();
}
