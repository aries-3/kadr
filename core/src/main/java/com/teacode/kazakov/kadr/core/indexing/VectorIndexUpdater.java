package com.teacode.kazakov.kadr.core.indexing;

import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.embeddings.StoredEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the vector index up with the vectors in the catalog. The catalog is the source of truth;
 * the index follows it by the write numbers of the vectors: it reads what was written after the
 * last write the index has and adds it, so one loop serves every case:
 * <ul>
 *   <li>new vectors are found within about a second of being written;</li>
 *   <li>after a crash the index takes again what came after its last commit;</li>
 *   <li>an index lost or made anew is filled from the catalog, without the model.</li>
 * </ul>
 * Commits are rare, as nothing is lost between them: what isn't committed is read again.
 */
public final class VectorIndexUpdater {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexUpdater.class);

    /**
     * How the work is done.
     *
     * @param batchSize      vectors read and added at once
     * @param pollInterval   how often to look for new vectors when there are none
     * @param commitInterval the longest time between commits while vectors are added
     * @param commitEvery    vectors added that make a commit at once
     */
    public record Settings(int batchSize, Duration pollInterval, Duration commitInterval, int commitEvery) {

        /** For the service: a batch a second at most when idle, a commit a minute or every 10,000 vectors. */
        public static final Settings DEFAULT = new Settings(1000, Duration.ofSeconds(1), Duration.ofMinutes(1), 10_000);

        /**
         * @param batchSize      vectors read and added at once
         * @param pollInterval   how often to look for new vectors when there are none
         * @param commitInterval the longest time between commits while vectors are added
         * @param commitEvery    vectors added that make a commit at once
         * @throws IllegalArgumentException if a number is less than 1 or a time isn't positive
         */
        public Settings {
            if (batchSize < 1 || commitEvery < 1 || !pollInterval.isPositive() || !commitInterval.isPositive()) {
                throw new IllegalArgumentException("Vector index settings out of range: batch %d, poll %s, commit %s or every %d"
                        .formatted(batchSize, pollInterval, commitInterval, commitEvery));
            }
        }
    }

    private final EmbeddingRepository embeddings;
    private final VectorIndex index;
    private final String modelId;
    private final Settings settings;

    public VectorIndexUpdater(EmbeddingRepository embeddings, VectorIndex index, String modelId, Settings settings) {
        this.embeddings = embeddings;
        this.index = index;
        this.modelId = modelId;
        this.settings = settings;
    }

    /**
     * Follows the catalog until asked to stop, then commits what it added.
     *
     * @param stopRequested polled between batches and while waiting
     */
    public void run(BooleanSupplier stopRequested) {
        long seq = index.durableSeq();
        long behind = embeddings.countAfter(modelId, seq);
        log.info("Vector index of {}: {} photos, {} vectors to add from the catalog", modelId, index.size(), behind);
        boolean catchingUp = behind > 0;
        long uncommitted = 0;
        long committedAt = System.nanoTime();
        while (!stopRequested.getAsBoolean()) {
            List<StoredEmbedding> batch = embeddings.after(modelId, seq, settings.batchSize());
            if (!batch.isEmpty()) {
                seq = batch.getLast().seq();
                index.upsert(batch.stream().map(StoredEmbedding::embedding).toList(), seq);
                uncommitted += batch.size();
            }
            boolean caughtUp = batch.size() < settings.batchSize();
            if (uncommitted > 0 && (uncommitted >= settings.commitEvery()
                    || System.nanoTime() - committedAt >= settings.commitInterval().toNanos()
                    || caughtUp && catchingUp)) {
                index.commit();
                uncommitted = 0;
                committedAt = System.nanoTime();
            }
            if (caughtUp) {
                if (catchingUp) {
                    catchingUp = false;
                    log.info("Vector index of {} caught up: {} photos", modelId, index.size());
                }
                pause(stopRequested);
            }
        }
        if (uncommitted > 0) {
            index.commit();
        }
    }

    private void pause(BooleanSupplier stopRequested) {
        long until = System.nanoTime() + settings.pollInterval().toNanos();
        try {
            while (!stopRequested.getAsBoolean() && System.nanoTime() < until) {
                Thread.sleep(Math.min(100, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
