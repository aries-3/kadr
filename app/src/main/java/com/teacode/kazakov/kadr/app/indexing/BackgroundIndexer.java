package com.teacode.kazakov.kadr.app.indexing;

import com.teacode.kazakov.kadr.app.config.KadrProperties;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the indexer on a background thread from startup to shutdown, unless indexing is turned off.
 * <p>
 * Stops before the database closes and the model is released, as a lifecycle bean: the indexer
 * finishes the batch in the model and puts back what it took and didn't finish. The thread isn't
 * interrupted: an interrupt in the middle of a database write can close H2.
 */
@Component
class BackgroundIndexer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BackgroundIndexer.class);
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(1);

    private final Indexer indexer;
    private final boolean enabled;

    private volatile boolean running;
    private volatile boolean stopping;
    private @Nullable Thread thread;

    BackgroundIndexer(Indexer indexer, KadrProperties properties) {
        this.indexer = indexer;
        this.enabled = properties.indexing().enabled();
    }

    @Override
    public void start() {
        running = true;
        if (!enabled) {
            log.info("Indexing is off: kadr.indexing.enabled is false");
            return;
        }
        stopping = false;
        thread = Thread.ofPlatform().name("kadr-index").daemon().start(() -> {
            try {
                indexer.run(() -> stopping);
            } catch (RuntimeException e) {
                log.error("Indexing stopped", e);
            }
        });
    }

    @Override
    public void stop() {
        stopping = true;
        Thread indexing = thread;
        if (indexing != null) {
            try {
                if (!indexing.join(STOP_TIMEOUT)) {
                    log.warn("Indexing didn't stop in {} s", STOP_TIMEOUT.toSeconds());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
