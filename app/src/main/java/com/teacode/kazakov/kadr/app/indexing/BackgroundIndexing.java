package com.teacode.kazakov.kadr.app.indexing;

import com.teacode.kazakov.kadr.app.config.KadrProperties;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.core.indexing.VectorIndexUpdater;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the indexing in the background from startup to shutdown, on two threads: the indexer,
 * which turns photos into vectors in the catalog, unless indexing is turned off; and the vector
 * index updater, which follows the catalog with the index, always.
 * <p>
 * Stops before the database closes and the model is released, as a lifecycle bean: the indexer
 * first, which finishes the batch in the model and puts back what it took; then the updater, which
 * takes the last vectors and commits the index. The threads aren't interrupted: an interrupt in the
 * middle of a database write can close H2.
 */
@Component
class BackgroundIndexing implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BackgroundIndexing.class);
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(1);

    private final Indexer indexer;
    private final VectorIndexUpdater updater;
    private final boolean indexingEnabled;

    private volatile boolean running;
    private volatile boolean stoppingIndexer;
    private volatile boolean stoppingUpdater;
    private @Nullable Thread indexing;
    private @Nullable Thread updating;

    BackgroundIndexing(Indexer indexer, VectorIndexUpdater updater, KadrProperties properties) {
        this.indexer = indexer;
        this.updater = updater;
        this.indexingEnabled = properties.indexing().enabled();
    }

    @Override
    public void start() {
        running = true;
        stoppingIndexer = false;
        stoppingUpdater = false;
        updating = start("kadr-vectors", "Updating the vector index", () -> updater.run(() -> stoppingUpdater));
        if (indexingEnabled) {
            indexing = start("kadr-index", "Indexing", () -> indexer.run(() -> stoppingIndexer));
        } else {
            log.info("Indexing is off: kadr.indexing.enabled is false");
        }
    }

    private static Thread start(String name, String work, Runnable task) {
        return Thread.ofPlatform().name(name).daemon().start(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("{} stopped", work, e);
            }
        });
    }

    @Override
    public void stop() {
        stoppingIndexer = true;
        join(indexing, "Indexing");
        stoppingUpdater = true;
        join(updating, "Updating the vector index");
        running = false;
    }

    private static void join(@Nullable Thread thread, String work) {
        if (thread == null) {
            return;
        }
        try {
            if (!thread.join(STOP_TIMEOUT)) {
                log.warn("{} didn't stop in {} s", work, STOP_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
