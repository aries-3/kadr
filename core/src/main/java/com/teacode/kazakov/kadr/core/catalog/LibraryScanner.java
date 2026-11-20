package com.teacode.kazakov.kadr.core.catalog;

import com.teacode.kazakov.kadr.domain.catalog.CatalogFile;
import com.teacode.kazakov.kadr.domain.catalog.Library;
import com.teacode.kazakov.kadr.domain.catalog.LibraryRepository;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.catalog.ScanOutcome;
import com.teacode.kazakov.kadr.domain.catalog.ScanState;
import com.teacode.kazakov.kadr.domain.catalog.ScannedFile;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.files.ContentHasher;
import com.teacode.kazakov.kadr.domain.files.FileEntry;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.files.FolderUnavailableException;
import com.teacode.kazakov.kadr.domain.files.PhotoFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a folder and brings the catalog in line with it.
 * <p>
 * A file whose size and modification time match the catalog is unchanged and isn't read. A new
 * or changed file is hashed and linked to the photo with that hash, so a moved or copied file
 * keeps its photo and its embedding. Files the catalog has inside the folder that the scan
 * didn't find are marked missing.
 * <p>
 * Work is saved in batches, so an interrupted scan loses little: run again, it skips what was
 * saved as unchanged. The caller runs one scan at a time.
 */
public final class LibraryScanner {

    public static final int DEFAULT_BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(LibraryScanner.class);
    private static final int PROGRESS_LOG_INTERVAL = 10_000;

    private final LibraryRepository libraries;
    private final PhotoRepository photos;
    private final FileTree fileTree;
    private final ContentHasher hasher;
    private final EnricherVersion enricher;
    private final Clock clock;
    private final int batchSize;

    /**
     * @param enricher  new photos are queued for it
     * @param batchSize files saved per transaction
     */
    public LibraryScanner(
            LibraryRepository libraries,
            PhotoRepository photos,
            FileTree fileTree,
            ContentHasher hasher,
            EnricherVersion enricher,
            Clock clock,
            int batchSize) {
        this.libraries = libraries;
        this.photos = photos;
        this.fileTree = fileTree;
        this.hasher = hasher;
        this.enricher = enricher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Scans a folder if a scan is requested for it; otherwise does nothing.
     *
     * @param stopRequested polled during the walk; once it returns {@code true}, the scan saves what
     *                      it has done and stops, staying requested to run again after a restart
     */
    public void scan(long libraryId, BooleanSupplier stopRequested) {
        Library library = libraries.findById(libraryId).orElse(null);
        if (library == null || library.scanState() != ScanState.SCANNING) {
            return;
        }
        Instant startedAt = clock.instant();
        libraries.scanStarted(libraryId, startedAt);
        Path folder = library.path();
        log.info("Scanning {}", folder);
        try {
            fileTree.realFolder(folder);
            Run run = new Run(folder, photos.files(library.files()));
            fileTree.walk(folder, stopRequested, run::visit);
            run.save();
            if (stopRequested.getAsBoolean()) {
                log.info("Scan of {} stopped; it resumes after a restart", folder);
                return;
            }
            // The folder may have gone during the walk, e.g. a disk was unmounted:
            // then the files it didn't find aren't missing, they are unreachable
            fileTree.realFolder(folder);
            int missing = run.markMissing();
            libraries.scanFinished(libraryId, ScanOutcome.COMPLETED, null, clock.instant());
            log.info("Scanned {} in {} s: {} files, {} new or changed, {} unreadable, {} missing",
                    folder, Duration.between(startedAt, clock.instant()).toSeconds(),
                    run.found, run.read, run.unreadable, missing);
        } catch (FolderUnavailableException e) {
            log.warn("Scan of {} skipped: {}", folder, e.getMessage());
            libraries.scanFinished(libraryId, ScanOutcome.UNAVAILABLE, e.getMessage(), clock.instant());
        } catch (RuntimeException e) {
            log.error("Scan of {} failed", folder, e);
            libraries.scanFinished(libraryId, ScanOutcome.FAILED, describe(e), clock.instant());
        }
    }

    private static String describe(Exception e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getName());
    }

    /**
     * State of one scan.
     */
    private final class Run {

        private final Path folder;
        /** Catalog files inside the folder that the walk hasn't reached yet. */
        private final Map<String, CatalogFile> unseen;
        private final List<ScannedFile> changed = new ArrayList<>();
        /** Files marked missing that are back unchanged. */
        private final List<Long> reappeared = new ArrayList<>();

        private long found;
        private long read;
        private long unreadable;

        Run(Path folder, List<CatalogFile> known) {
            this.folder = folder;
            this.unseen = new HashMap<>();
            for (CatalogFile file : known) {
                unseen.put(file.path(), file);
            }
        }

        void visit(FileEntry file) {
            String path = file.path().toString();
            CatalogFile known = unseen.remove(path);
            if (++found % PROGRESS_LOG_INTERVAL == 0) {
                log.info("Scanning {}: {} files so far", folder, found);
            }
            if (known != null && known.sizeBytes() == file.sizeBytes() && known.mtimeMs() == file.mtimeMs()) {
                if (known.missing()) {
                    reappeared.add(known.id());
                    if (reappeared.size() >= batchSize) {
                        saveReappeared();
                    }
                }
                return;
            }
            try {
                changed.add(new ScannedFile(path, file.sizeBytes(), file.mtimeMs(), hasher.hash(file.path())));
                read++;
            } catch (IOException e) {
                // Can't be read now: left unseen, so a known file is marked missing
                if (known != null) {
                    unseen.put(path, known);
                }
                unreadable++;
                log.warn("Can't read {}: {}", path, e.toString());
                return;
            }
            if (changed.size() >= batchSize) {
                saveChanged();
            }
        }

        void save() {
            saveChanged();
            saveReappeared();
        }

        /**
         * Marks missing the catalog files the walk should have reached but didn't. Files of nested
         * folders the walk skips, e.g. a hidden folder added on its own, are left alone.
         *
         * @return number of files marked missing
         */
        int markMissing() {
            List<Long> missing = unseen.values().stream()
                    .filter(file -> !file.missing())
                    .filter(file -> PhotoFiles.isReachable(folder, Path.of(file.path())))
                    .map(CatalogFile::id)
                    .toList();
            for (int from = 0; from < missing.size(); from += batchSize) {
                photos.markMissing(missing.subList(from, Math.min(from + batchSize, missing.size())));
            }
            return missing.size();
        }

        private void saveChanged() {
            if (!changed.isEmpty()) {
                photos.record(List.copyOf(changed), enricher, clock.instant());
                changed.clear();
            }
        }

        private void saveReappeared() {
            if (!reappeared.isEmpty()) {
                photos.markPresent(List.copyOf(reappeared));
                reappeared.clear();
            }
        }
    }
}
