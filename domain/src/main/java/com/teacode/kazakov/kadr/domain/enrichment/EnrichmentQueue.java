package com.teacode.kazakov.kadr.domain.enrichment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The tasks of an enricher, one per photo: {@code PENDING} until taken, {@code RUNNING} while
 * processed, then {@code DONE}, or {@code FAILED} once retries are used up. A task of an older
 * version of the enricher is done again.
 * <p>
 * One worker takes the tasks of an enricher; the queue doesn't guard against two.
 */
public interface EnrichmentQueue {

    /**
     * Puts the queue in order before the work starts: tasks left running by a stopped process are
     * pending again; tasks of an older version are pending with the current one and their attempts
     * reset; photos without a task get one.
     *
     * @param enricher the enricher and its current version
     * @param now      when this happens
     * @return how many tasks each step touched
     */
    QueueRecovery recover(EnricherVersion enricher, Instant now);

    /**
     * Takes pending tasks, in the order the photos were found; they become running. A task that
     * failed before is taken only once it has waited since its last attempt.
     *
     * @param enricher    the enricher and its current version
     * @param limit       the most tasks to take
     * @param retryBefore a task that failed is taken only if its last attempt was before this
     * @param now         when they are taken
     * @return the ids of the photos whose tasks were taken; empty if none is due
     */
    List<Long> claim(EnricherVersion enricher, int limit, Instant retryBefore, Instant now);

    /**
     * Puts running tasks back to pending without counting an attempt: they weren't finished,
     * e.g. because the work is stopping.
     *
     * @param photoIds the photos of the tasks
     * @param enricher the enricher
     * @param now      when this happens
     */
    void release(Collection<Long> photoIds, EnricherVersion enricher, Instant now);

    /**
     * Records failed attempts. A task is pending again while it may be retried and has attempts
     * left, failed otherwise; its error is kept either way.
     *
     * @param failures    what failed and why
     * @param enricher    the enricher
     * @param maxAttempts attempts a task gets in all
     * @param now         when the attempts failed
     */
    void fail(List<TaskFailure> failures, EnricherVersion enricher, int maxAttempts, Instant now);

    /**
     * Counts the tasks of an enricher, of all photos.
     *
     * @param enricher the enricher
     * @return the counts
     */
    QueueCounts counts(EnricherVersion enricher);
}
