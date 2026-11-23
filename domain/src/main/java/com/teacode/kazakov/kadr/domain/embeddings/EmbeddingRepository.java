package com.teacode.kazakov.kadr.domain.embeddings;

import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import java.time.Instant;
import java.util.List;

/**
 * The vectors of photos, by model. Every write gets the next number of a sequence, so the vector
 * index can catch up with what was written after its last commit.
 */
public interface EmbeddingRepository {

    /**
     * Stores vectors, replacing those of the same photos and model, and marks the tasks that made
     * them done, in one transaction: a vector is never stored without its task done, nor the other
     * way round.
     *
     * @param embeddings the vectors
     * @param modelId    the model that made them
     * @param enricher   the enricher whose tasks they finish
     * @param now        when they were made
     */
    void save(List<PhotoEmbedding> embeddings, String modelId, EnricherVersion enricher, Instant now);

    /**
     * Reads the vectors written after a given write, in the order they were written. As vectors
     * are written by one thread, the write numbers become visible in order: none appears later
     * below one already read.
     *
     * @param modelId the model
     * @param seq     the write number to read after; 0 for all
     * @param limit   the most vectors to read
     * @return the vectors with their write numbers
     */
    List<StoredEmbedding> after(String modelId, long seq, int limit);

    /**
     * Counts the vectors written after a given write.
     *
     * @param modelId the model
     * @param seq     the write number to count after; 0 for all
     * @return how many
     */
    long countAfter(String modelId, long seq);
}
