package com.teacode.kazakov.kadr.domain.enrichment;

/**
 * What putting the queue in order changed.
 *
 * @param resumed  tasks left running by a stopped process, pending again
 * @param requeued tasks of an older version, pending with the current one
 * @param created  tasks made for photos that had none
 */
public record QueueRecovery(int resumed, int requeued, int created) {
}
