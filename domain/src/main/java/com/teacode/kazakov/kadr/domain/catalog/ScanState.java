package com.teacode.kazakov.kadr.domain.catalog;

/**
 * Whether a folder is due to be scanned. Stored, so a scan interrupted by a restart runs again.
 */
public enum ScanState {
    /** No scan is requested. */
    IDLE,
    /** A scan is requested or running. */
    SCANNING
}
