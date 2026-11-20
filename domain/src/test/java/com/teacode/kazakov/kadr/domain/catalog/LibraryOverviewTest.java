package com.teacode.kazakov.kadr.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class LibraryOverviewTest {

    private static final LibraryProgress PENDING = new LibraryProgress(10, 4, 5, 1);
    private static final LibraryProgress DONE = new LibraryProgress(10, 9, 0, 1);

    @Test
    void scanningWhileAScanIsRequestedOrRunning() {
        assertThat(overview(ScanState.SCANNING, null, PENDING).status()).isEqualTo(LibraryStatus.SCANNING);
        assertThat(overview(ScanState.SCANNING, ScanOutcome.UNAVAILABLE, PENDING).status())
                .isEqualTo(LibraryStatus.SCANNING);
    }

    @Test
    void unavailableAfterAScanFoundNoFolder() {
        assertThat(overview(ScanState.IDLE, ScanOutcome.UNAVAILABLE, DONE).status())
                .isEqualTo(LibraryStatus.UNAVAILABLE);
    }

    @Test
    void indexingWhilePhotosArePending() {
        assertThat(overview(ScanState.IDLE, ScanOutcome.COMPLETED, PENDING).status())
                .isEqualTo(LibraryStatus.INDEXING);
        assertThat(overview(ScanState.IDLE, ScanOutcome.FAILED, PENDING).status())
                .isEqualTo(LibraryStatus.INDEXING);
    }

    @Test
    void readyWhenNothingIsPending() {
        assertThat(overview(ScanState.IDLE, ScanOutcome.COMPLETED, DONE).status()).isEqualTo(LibraryStatus.READY);
    }

    private static LibraryOverview overview(ScanState state, @Nullable ScanOutcome outcome, LibraryProgress progress) {
        Library library = new Library(1, Path.of("/photos"), state, outcome, null, null, null, Instant.EPOCH);
        return new LibraryOverview(library, progress);
    }
}
