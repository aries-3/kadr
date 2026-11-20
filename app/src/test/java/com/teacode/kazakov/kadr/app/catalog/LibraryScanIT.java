package com.teacode.kazakov.kadr.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.domain.catalog.LibraryOverview;
import com.teacode.kazakov.kadr.domain.catalog.LibraryProgress;
import com.teacode.kazakov.kadr.domain.catalog.LibraryStatus;
import com.teacode.kazakov.kadr.domain.catalog.PathRange;
import com.teacode.kazakov.kadr.domain.catalog.ScanOutcome;
import com.teacode.kazakov.kadr.domain.files.ContentHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Scan scenarios on real folders, with the real database. Scans run in the test thread:
 * background launching is replaced by a mock.
 */
@SpringBootTest
class LibraryScanIT {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
    }

    @MockitoBean
    ScanLauncher launcher;

    /** Shows which files a scan reads. */
    @MockitoSpyBean
    ContentHasher hasher;

    @Autowired
    LibraryService libraries;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    JdbcClient sql;

    @TempDir
    Path temp;

    private Path photos;
    private final Random random = new Random();

    @BeforeEach
    void createPhotoFolder() throws IOException {
        photos = Files.createDirectory(temp.resolve("photos")).toRealPath();
    }

    @Test
    void photosAreCatalogedAndQueued() throws IOException {
        Path a = photo("a.jpg");
        Path b = photo("2023/07/b.png");
        Path c = photo("2023/c.webp");
        photo("notes.txt");
        photo(".hidden.jpg");
        photo(".thumbnails/d.jpg");
        photo("@eaDir/e.jpg");
        Files.createSymbolicLink(photos.resolve("link.jpg"), a);

        LibraryOverview library = addAndScan(photos);

        assertThat(library.status()).isEqualTo(LibraryStatus.INDEXING);
        assertThat(library.progress()).isEqualTo(new LibraryProgress(3, 0, 3, 0));
        assertThat(library.library().scanOutcome()).isEqualTo(ScanOutcome.COMPLETED);
        assertThat(library.library().lastScanFinishedAt()).isNotNull();
        assertThat(presentFiles(photos)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void unchangedFilesAreNotRead() throws IOException {
        photo("a.jpg");
        photo("2023/b.jpg");
        long id = add(photos);
        scan(id);
        clearInvocations(hasher);

        LibraryOverview library = rescan(id);

        verify(hasher, never()).hash(any());
        assertThat(library.progress()).isEqualTo(new LibraryProgress(2, 0, 2, 0));
    }

    @Test
    void changedFileIsLinkedToItsNewContent() throws IOException {
        Path a = photo("a.jpg");
        long id = add(photos);
        scan(id);
        long file = fileId(a);
        long oldPhoto = photoOf(a);

        Files.write(a, randomContent());
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 1000));
        clearInvocations(hasher);
        LibraryOverview library = rescan(id);

        verify(hasher).hash(a);
        assertThat(fileId(a)).isEqualTo(file);
        assertThat(photoOf(a)).isNotEqualTo(oldPhoto);
        assertThat(library.progress()).isEqualTo(new LibraryProgress(1, 0, 1, 0));
    }

    @Test
    void movedFileKeepsItsPhoto() throws IOException {
        Path a = photo("inbox/a.jpg");
        long id = add(photos);
        scan(id);
        long photo = photoOf(a);

        Path moved = Files.move(a, Files.createDirectory(photos.resolve("2023")).resolve("a.jpg"));
        LibraryOverview library = rescan(id);

        assertThat(missing(a)).isTrue();
        assertThat(photoOf(moved)).isEqualTo(photo);
        assertThat(tasksFor(photo)).isEqualTo(1);
        assertThat(library.progress()).isEqualTo(new LibraryProgress(1, 0, 1, 0));
    }

    @Test
    void deletedFileIsMissingUntilItComesBack() throws IOException {
        Path a = photo("a.jpg");
        photo("b.jpg");
        long id = add(photos);
        scan(id);

        Path aside = Files.move(a, temp.resolve("a.jpg"));

        assertThat(rescan(id).progress().files()).isEqualTo(1);
        assertThat(missing(a)).isTrue();

        Files.move(aside, a);
        clearInvocations(hasher);

        assertThat(rescan(id).progress().files()).isEqualTo(2);
        assertThat(missing(a)).isFalse();
        verify(hasher, never()).hash(any());
    }

    @Test
    void copyIsTheSamePhoto() throws IOException {
        Path a = photo("a.jpg");
        long id = add(photos);
        scan(id);

        Path copy = Files.copy(a, Files.createDirectory(photos.resolve("best")).resolve("a.jpg"));
        LibraryOverview library = rescan(id);

        assertThat(photoOf(copy)).isEqualTo(photoOf(a));
        assertThat(library.progress()).isEqualTo(new LibraryProgress(2, 0, 1, 0));
    }

    @Test
    void subfolderOfAScannedFolderNeedsNoWork() throws IOException {
        photo("a.jpg");
        photo("2023/b.jpg");
        photo("2023/c.jpg");
        addAndScan(photos);
        long tasks = count("enrichment");
        clearInvocations(hasher);

        LibraryOverview subfolder = addAndScan(photos.resolve("2023"));

        verify(hasher, never()).hash(any());
        assertThat(count("enrichment")).isEqualTo(tasks);
        assertThat(subfolder.progress()).isEqualTo(new LibraryProgress(2, 0, 2, 0));
    }

    @Test
    void parentAddedAfterItsSubfolderReadsOnlyTheRest() throws IOException {
        Path a = photo("a.jpg");
        photo("2023/b.jpg");
        photo("2023/c.jpg");
        addAndScan(photos.resolve("2023"));
        clearInvocations(hasher);

        LibraryOverview parent = addAndScan(photos);

        verify(hasher).hash(a);
        verifyNoMoreInteractions(hasher);
        assertThat(parent.progress()).isEqualTo(new LibraryProgress(3, 0, 3, 0));
    }

    @Test
    void parentScanLeavesAHiddenNestedFolderAlone() throws IOException {
        Path hidden = photo(".private/a.jpg");
        photo("b.jpg");
        long child = add(photos.resolve(".private"));
        scan(child);

        long parent = add(photos);
        scan(parent);
        rescan(parent);

        assertThat(missing(hidden)).isFalse();
        assertThat(libraries.get(child).progress().files()).isEqualTo(1);
    }

    @Test
    void siblingFolderWithTheSamePrefixIsSeparate() throws IOException {
        photo("a.jpg");
        Path sibling = Files.createDirectory(photos.resolveSibling("photos-old"));
        Path old = Files.write(sibling.resolve("b.jpg"), randomContent());
        addAndScan(sibling);

        long id = add(photos);

        assertThat(scan(id).progress().files()).isEqualTo(1);
        rescan(id);
        assertThat(missing(old)).isFalse();
    }

    @Test
    void unavailableFolderKeepsItsFiles() throws IOException {
        Path a = photo("a.jpg");
        long id = add(photos);
        scan(id);

        Path unmounted = Files.move(photos, photos.resolveSibling("unmounted"));
        LibraryOverview library = rescan(id);

        assertThat(library.status()).isEqualTo(LibraryStatus.UNAVAILABLE);
        assertThat(library.library().scanError()).contains(photos.toString());
        assertThat(missing(a)).isFalse();

        Files.move(unmounted, photos);
        library = rescan(id);

        assertThat(library.status()).isEqualTo(LibraryStatus.INDEXING);
        assertThat(library.library().scanError()).isNull();
    }

    @Test
    void unreadableFileIsMissing() throws IOException {
        Path a = photo("a.jpg");
        photo("b.jpg");
        long id = add(photos);
        scan(id);

        Files.write(a, randomContent());
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 1000));
        Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(a), "running as root: permissions aren't enforced");
        LibraryOverview library = rescan(id);

        assertThat(library.library().scanOutcome()).isEqualTo(ScanOutcome.COMPLETED);
        assertThat(library.progress().files()).isEqualTo(1);
        assertThat(missing(a)).isTrue();
    }

    @Test
    void interruptedScanResumesWhereItStopped() throws IOException {
        for (int i = 0; i < 5; i++) {
            photo("p" + i + ".jpg");
        }
        long id = add(photos);
        // Stops once two files are read
        scanner.scan(id, () -> mockingDetails(hasher).getInvocations().size() >= 2);

        LibraryOverview stopped = libraries.get(id);
        assertThat(stopped.status()).isEqualTo(LibraryStatus.SCANNING);
        assertThat(stopped.progress().files()).isEqualTo(2);

        clearInvocations(launcher, hasher);
        libraries.resumeInterruptedScans();
        verify(launcher).launch(id);

        assertThat(scan(id).progress()).isEqualTo(new LibraryProgress(5, 0, 5, 0));
        verify(hasher, times(3)).hash(any());
    }

    @Test
    void scanRunsOnlyWhenRequested() throws IOException {
        photo("a.jpg");
        long id = add(photos);
        Instant started = scan(id).library().lastScanStartedAt();

        scanner.scan(id, () -> false);

        assertThat(libraries.get(id).library().lastScanStartedAt()).isEqualTo(started);
    }

    private Path photo(String relative) throws IOException {
        Path file = photos.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, randomContent());
    }

    /** Unique content, so photos of different tests never share a hash. */
    private byte[] randomContent() {
        byte[] content = new byte[1024 + random.nextInt(1024)];
        random.nextBytes(content);
        return content;
    }

    private long add(Path folder) {
        return libraries.add(folder.toString()).library().id();
    }

    private LibraryOverview scan(long id) {
        scanner.scan(id, () -> false);
        return libraries.get(id);
    }

    private LibraryOverview addAndScan(Path folder) {
        return scan(add(folder));
    }

    private LibraryOverview rescan(long id) {
        libraries.rescan(id);
        return scan(id);
    }

    private List<Path> presentFiles(Path folder) {
        PathRange range = PathRange.under(folder);
        return sql.sql("SELECT path FROM photo_file WHERE path >= :from AND path < :to AND NOT missing")
                .param("from", range.from())
                .param("to", range.to())
                .query((rs, row) -> Path.of(rs.getString("path")))
                .list();
    }

    private long fileId(Path file) {
        return sql.sql("SELECT id FROM photo_file WHERE path = :path")
                .param("path", file.toString())
                .query(Long.class)
                .single();
    }

    private long photoOf(Path file) {
        return sql.sql("SELECT photo_id FROM photo_file WHERE path = :path")
                .param("path", file.toString())
                .query(Long.class)
                .single();
    }

    private boolean missing(Path file) {
        return sql.sql("SELECT missing FROM photo_file WHERE path = :path")
                .param("path", file.toString())
                .query(Boolean.class)
                .single();
    }

    private long tasksFor(long photo) {
        return sql.sql("SELECT COUNT(*) FROM enrichment WHERE photo_id = :id")
                .param("id", photo)
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return sql.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
