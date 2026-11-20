package com.teacode.kazakov.kadr.h2;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.Library;
import com.teacode.kazakov.kadr.domain.catalog.ScanOutcome;
import com.teacode.kazakov.kadr.domain.catalog.ScanState;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcLibraryRepositoryTest {

    private static final Instant ADDED = Instant.parse("2026-11-14T10:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-11-14T10:00:01Z");
    private static final Instant FINISHED = Instant.parse("2026-11-14T10:05:00.123456Z");

    private final JdbcLibraryRepository libraries =
            new JdbcLibraryRepository(JdbcClient.create(new TestDatabase().jdbcTemplate));

    @Test
    void addedFolderHasAScanRequested() {
        Library library = libraries.add(Path.of("/photos"), ADDED).orElseThrow();

        assertThat(library.path()).isEqualTo(Path.of("/photos"));
        assertThat(library.scanState()).isEqualTo(ScanState.SCANNING);
        assertThat(library.scanOutcome()).isNull();
        assertThat(library.scanError()).isNull();
        assertThat(library.lastScanStartedAt()).isNull();
        assertThat(library.lastScanFinishedAt()).isNull();
        assertThat(library.createdAt()).isEqualTo(ADDED);
        assertThat(libraries.findById(library.id())).contains(library);
    }

    @Test
    void aFolderIsAddedOnce() {
        libraries.add(Path.of("/photos"), ADDED);

        assertThat(libraries.add(Path.of("/photos"), ADDED)).isEmpty();
    }

    @Test
    void nestedFoldersAreSeparateLibraries() {
        Library child = libraries.add(Path.of("/photos/2023"), ADDED).orElseThrow();
        Library parent = libraries.add(Path.of("/photos"), ADDED).orElseThrow();

        assertThat(libraries.findAll()).containsExactly(child, parent);
    }

    @Test
    void scanIsRequestedOnlyWhenNoneIsPending() {
        long id = libraries.add(Path.of("/photos"), ADDED).orElseThrow().id();

        assertThat(libraries.requestScan(id)).isFalse();

        libraries.scanFinished(id, ScanOutcome.COMPLETED, null, FINISHED);

        assertThat(libraries.requestScan(id)).isTrue();
        assertThat(libraries.requestScan(id)).isFalse();
        assertThat(libraries.findById(id).orElseThrow().scanState()).isEqualTo(ScanState.SCANNING);
    }

    @Test
    void scanResultIsStored() {
        long id = libraries.add(Path.of("/photos"), ADDED).orElseThrow().id();

        libraries.scanStarted(id, STARTED);
        libraries.scanFinished(id, ScanOutcome.UNAVAILABLE, "The folder doesn't exist", FINISHED);

        Library library = libraries.findById(id).orElseThrow();
        assertThat(library.scanState()).isEqualTo(ScanState.IDLE);
        assertThat(library.scanOutcome()).isEqualTo(ScanOutcome.UNAVAILABLE);
        assertThat(library.scanError()).isEqualTo("The folder doesn't exist");
        assertThat(library.lastScanStartedAt()).isEqualTo(STARTED);
        assertThat(library.lastScanFinishedAt()).isEqualTo(FINISHED);
    }

    @Test
    void successfulScanClearsTheError() {
        long id = libraries.add(Path.of("/photos"), ADDED).orElseThrow().id();
        libraries.scanFinished(id, ScanOutcome.UNAVAILABLE, "The folder doesn't exist", FINISHED);

        libraries.scanFinished(id, ScanOutcome.COMPLETED, null, FINISHED);

        assertThat(libraries.findById(id).orElseThrow().scanError()).isNull();
    }

    @Test
    void longErrorIsTruncated() {
        long id = libraries.add(Path.of("/photos"), ADDED).orElseThrow().id();

        libraries.scanFinished(id, ScanOutcome.FAILED, "x".repeat(5000), FINISHED);

        assertThat(libraries.findById(id).orElseThrow().scanError())
                .hasSize(JdbcLibraryRepository.MAX_ERROR_LENGTH);
    }

    @Test
    void findsFoldersByScanState() {
        Library scanning = libraries.add(Path.of("/a"), ADDED).orElseThrow();
        Library idle = libraries.add(Path.of("/b"), ADDED).orElseThrow();
        libraries.scanFinished(idle.id(), ScanOutcome.COMPLETED, null, FINISHED);

        assertThat(libraries.findByScanState(ScanState.SCANNING)).extracting(Library::id).containsExactly(scanning.id());
        assertThat(libraries.findByScanState(ScanState.IDLE)).extracting(Library::id).containsExactly(idle.id());
    }
}
