package com.teacode.kazakov.kadr.domain.catalog;

/**
 * How the last scan of a folder ended.
 */
public enum ScanOutcome {
    /** The folder was walked to the end; the catalog matches it. */
    COMPLETED,
    /** The folder couldn't be read, e.g. a disk is unmounted. Its files are left as they were. */
    UNAVAILABLE,
    /** The scan stopped on an unexpected error. */
    FAILED
}
