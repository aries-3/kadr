package com.teacode.kazakov.kadr.domain.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Folders added to the catalog.
 */
public interface LibraryRepository {

    /**
     * Adds a folder with a scan requested.
     *
     * @param path absolute real path
     * @param now  when the folder is added
     * @return the added folder, or empty if this folder is already added
     */
    Optional<Library> add(Path path, Instant now);

    /**
     * Finds a folder by its id.
     *
     * @param id catalog id of the folder
     * @return the folder, or empty if there is none with this id
     */
    Optional<Library> findById(long id);

    /**
     * {@return all folders, in the order they were added}
     */
    List<Library> findAll();

    /**
     * Finds the folders in a scan state, e.g. the scans to resume after a restart.
     *
     * @param state the scan state
     * @return the folders in this state, in the order they were added
     */
    List<Library> findByScanState(ScanState state);

    /**
     * Requests a scan unless one is already requested or running.
     *
     * @param id catalog id of the folder
     * @return {@code true} if this call requested the scan
     */
    boolean requestScan(long id);

    /**
     * Records the start of a requested scan.
     *
     * @param id catalog id of the folder
     * @param at when the scan started
     */
    void scanStarted(long id, Instant at);

    /**
     * Records the end of a scan; the folder becomes {@link ScanState#IDLE}.
     *
     * @param id      catalog id of the folder
     * @param outcome how the scan ended
     * @param error   why the scan didn't complete; {@code null} if it did
     * @param at      when the scan finished
     */
    void scanFinished(long id, ScanOutcome outcome, @Nullable String error, Instant at);
}
