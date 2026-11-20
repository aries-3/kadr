package com.teacode.kazakov.kadr.domain.catalog;

import java.nio.file.Path;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A folder added to the catalog.
 * <p>
 * The folder is a scan scope, not an owner of files: a file inside nested folders is stored
 * once, by its absolute path, and belongs to every folder that contains it.
 *
 * @param id                 catalog id of the folder
 * @param path               absolute real path of the folder
 * @param scanState          whether a scan is requested or running
 * @param scanOutcome        result of the last finished scan; {@code null} before the first one finishes
 * @param scanError          why the last finished scan didn't complete; {@code null} if it completed
 *                           or none has finished yet
 * @param lastScanStartedAt  when the last scan started; {@code null} before the first one starts
 * @param lastScanFinishedAt when the last scan finished; {@code null} before the first one finishes
 * @param createdAt          when the folder was added
 */
public record Library(
        long id,
        Path path,
        ScanState scanState,
        @Nullable ScanOutcome scanOutcome,
        @Nullable String scanError,
        @Nullable Instant lastScanStartedAt,
        @Nullable Instant lastScanFinishedAt,
        Instant createdAt) {

    /**
     * {@return catalog paths of the files inside this folder}
     */
    public PathRange files() {
        return PathRange.under(path);
    }
}
