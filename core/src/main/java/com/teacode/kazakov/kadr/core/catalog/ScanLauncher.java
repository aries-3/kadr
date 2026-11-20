package com.teacode.kazakov.kadr.core.catalog;

/**
 * Runs scans in the background, one at a time.
 */
public interface ScanLauncher {

    /**
     * Queues a scan of a folder and returns at once. The scan itself is done by {@link LibraryScanner}.
     */
    void launch(long libraryId);
}
