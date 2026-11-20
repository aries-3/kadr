package com.teacode.kazakov.kadr.app.scan;

import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs folder scans on one background thread, so scans never overlap: folders may be nested,
 * and the disk is shared anyway.
 * <p>
 * Stops before the database closes, as a lifecycle bean: a running scan saves its work and stops
 * at the next file, staying requested, so it runs again after a restart. Threads aren't
 * interrupted: an interrupt in the middle of a database write can close H2.
 */
@Component
class BackgroundScanLauncher implements ScanLauncher, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BackgroundScanLauncher.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final LibraryScanner scanner;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("kadr-scan").daemon().factory());

    private volatile boolean running;
    private volatile boolean stopping;

    BackgroundScanLauncher(LibraryScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void launch(long libraryId) {
        try {
            executor.execute(() -> scan(libraryId));
        } catch (RejectedExecutionException e) {
            log.info("Shutting down: the scan of library {} runs after the restart", libraryId);
        }
    }

    private void scan(long libraryId) {
        try {
            scanner.scan(libraryId, () -> stopping);
        } catch (RuntimeException e) {
            // The scanner records its own failures; this is a failure to record one
            log.error("Scan of library {} failed", libraryId, e);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("The scan didn't stop in {} s", STOP_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
