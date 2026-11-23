package com.teacode.kazakov.kadr.domain.enrichment;

/**
 * The tasks of an enricher, by status.
 *
 * @param pending waiting or being processed
 * @param done    processed
 * @param failed  failed for good
 */
public record QueueCounts(long pending, long done, long failed) {
}
