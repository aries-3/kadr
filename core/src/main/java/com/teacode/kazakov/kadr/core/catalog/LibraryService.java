package com.teacode.kazakov.kadr.core.catalog;

import com.teacode.kazakov.kadr.domain.catalog.Library;
import com.teacode.kazakov.kadr.domain.catalog.LibraryOverview;
import com.teacode.kazakov.kadr.domain.catalog.LibraryRepository;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.catalog.ScanState;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.files.FolderUnavailableException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folders added to the catalog: adding, listing, rescanning.
 */
public final class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    private final LibraryRepository libraries;
    private final PhotoRepository photos;
    private final FileTree fileTree;
    private final ScanLauncher scans;
    private final EnricherVersion enricher;
    private final Clock clock;

    /**
     * @param enricher the active enricher: its tasks are the indexing progress
     */
    public LibraryService(
            LibraryRepository libraries,
            PhotoRepository photos,
            FileTree fileTree,
            ScanLauncher scans,
            EnricherVersion enricher,
            Clock clock) {
        this.libraries = libraries;
        this.photos = photos;
        this.fileTree = fileTree;
        this.scans = scans;
        this.enricher = enricher;
        this.clock = clock;
    }

    /**
     * Adds a folder and starts scanning it in the background. Nested folders are allowed.
     *
     * @param folder absolute path of a readable folder
     * @throws InvalidFolderException        if the path isn't absolute or isn't a readable folder
     * @throws LibraryAlreadyAddedException if this folder is already added, under any spelling of the path
     */
    public LibraryOverview add(String folder) {
        Path realFolder;
        try {
            realFolder = fileTree.realFolder(absolutePath(folder));
        } catch (FolderUnavailableException e) {
            throw new InvalidFolderException(e.getMessage());
        }
        Library library = libraries.add(realFolder, clock.instant())
                .orElseThrow(() -> new LibraryAlreadyAddedException(realFolder));
        log.info("Added {}", realFolder);
        scans.launch(library.id());
        return overview(library);
    }

    public List<LibraryOverview> list() {
        return libraries.findAll().stream().map(this::overview).toList();
    }

    /**
     * @throws LibraryNotFoundException if there is no such folder
     */
    public LibraryOverview get(long libraryId) {
        return overview(find(libraryId));
    }

    /**
     * Starts a new scan of a folder unless one is already requested or running.
     *
     * @throws LibraryNotFoundException if there is no such folder
     */
    public LibraryOverview rescan(long libraryId) {
        find(libraryId);
        if (libraries.requestScan(libraryId)) {
            scans.launch(libraryId);
        }
        return get(libraryId);
    }

    /**
     * Launches the scans a restart interrupted. Called once at startup.
     */
    public void resumeInterruptedScans() {
        for (Library library : libraries.findByScanState(ScanState.SCANNING)) {
            log.info("Resuming the scan of {}", library.path());
            scans.launch(library.id());
        }
    }

    private Library find(long libraryId) {
        return libraries.findById(libraryId).orElseThrow(() -> new LibraryNotFoundException(libraryId));
    }

    private LibraryOverview overview(Library library) {
        return new LibraryOverview(library, photos.progress(library.files(), enricher.id()));
    }

    private static Path absolutePath(String folder) {
        Path path;
        try {
            path = Path.of(folder);
        } catch (InvalidPathException e) {
            throw new InvalidFolderException("Not a valid path: " + folder);
        }
        if (!path.isAbsolute()) {
            throw new InvalidFolderException("The path must be absolute: " + folder);
        }
        return path;
    }
}
