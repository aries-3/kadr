package com.teacode.kazakov.kadr.domain.catalog;

/**
 * State of a folder as shown to the user. Derived, not stored: see {@link LibraryOverview#status()}.
 */
public enum LibraryStatus {
    /** The folder is being scanned, or a scan is waiting for its turn. */
    SCANNING,
    /** The scan is finished; photos are waiting to be indexed. */
    INDEXING,
    /** Everything found is indexed or failed. */
    READY,
    /** The folder can't be read. */
    UNAVAILABLE
}
