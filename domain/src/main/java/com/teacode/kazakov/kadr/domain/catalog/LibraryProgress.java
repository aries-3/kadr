package com.teacode.kazakov.kadr.domain.catalog;

/**
 * Counts for the files inside a folder.
 *
 * @param files   files present on disk
 * @param indexed photos processed by the active enricher
 * @param pending photos waiting for the active enricher
 * @param failed  photos the active enricher couldn't process
 */
public record LibraryProgress(long files, long indexed, long pending, long failed) {
}
