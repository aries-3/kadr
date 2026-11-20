package com.teacode.kazakov.kadr.domain.catalog;

import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Photos and the files they are stored in, as scans see them.
 */
public interface PhotoRepository {

    /**
     * Finds the files inside a folder, present or missing.
     *
     * @param range paths inside the folder
     * @return the files, in no particular order
     */
    List<CatalogFile> files(PathRange range);

    /**
     * Records new and changed files in one transaction.
     * <p>
     * Each file is linked to the photo with its content hash, created if there is none; the file
     * is no longer missing. A photo without a task for the enricher gets a pending one.
     *
     * @param files    new and changed files; a file already in the catalog is found by its path
     * @param enricher the enricher every photo of the files must have a task for
     * @param now      creation time of new photos and tasks
     */
    void record(List<ScannedFile> files, EnricherVersion enricher, Instant now);

    /**
     * Marks files as not found by the last scan; their photos are kept.
     *
     * @param fileIds catalog ids of the files
     */
    void markMissing(Collection<Long> fileIds);

    /**
     * Marks missing files as found again, unchanged.
     *
     * @param fileIds catalog ids of the files
     */
    void markPresent(Collection<Long> fileIds);

    /**
     * Counts files and photos inside a folder; photos are counted once, whatever number of files they have.
     *
     * @param range      paths inside the folder
     * @param enricherId the enricher whose tasks count as indexed, pending or failed
     * @return the counts; missing files and their photos aren't counted
     */
    LibraryProgress progress(PathRange range, String enricherId);
}
