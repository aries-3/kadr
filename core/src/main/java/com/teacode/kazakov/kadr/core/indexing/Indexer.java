package com.teacode.kazakov.kadr.core.indexing;

import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.EnrichmentQueue;
import com.teacode.kazakov.kadr.domain.enrichment.QueueCounts;
import com.teacode.kazakov.kadr.domain.enrichment.QueueRecovery;
import com.teacode.kazakov.kadr.domain.enrichment.TaskFailure;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes photos: computes the vector of every photo with a pending task and stores it.
 * <p>
 * The work goes in chunks of tasks taken from the queue. Decoding threads read the photos of a
 * chunk into a bounded queue; the thread that runs the indexer takes them from there in batches,
 * runs the model on each batch and stores the vectors together with their tasks done. Only that
 * thread writes to the database, so the writes never wait for each other.
 * <p>
 * A photo is read from any of its present files. A photo that isn't an image fails at once;
 * one whose files can't be read is tried again a minute later, up to the attempts allowed.
 * <p>
 * The indexer doesn't interrupt threads: an interrupt in the middle of a database read or write
 * can close the database. Asked to stop, it finishes the batch in the model and releases the
 * rest of its chunk.
 */
public final class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);

    /** How long a task that failed waits before another attempt. */
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    /** Batches in a chunk of tasks. */
    private static final int BATCHES_A_CHUNK = 4;
    private static final Duration PROGRESS_INTERVAL = Duration.ofMinutes(1);
    private static final Duration DECODING_STOP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How the work is done.
     *
     * @param decodingThreads threads decoding photos alongside the model
     * @param batchSize       photos the model takes at once
     * @param maxAttempts     attempts a photo gets in all
     * @param idlePause       how long to wait for new tasks when there are none
     */
    public record Settings(int decodingThreads, int batchSize, int maxAttempts, Duration idlePause) {

        /**
         * @param decodingThreads threads decoding photos alongside the model
         * @param batchSize       photos the model takes at once
         * @param maxAttempts     attempts a photo gets in all
         * @param idlePause       how long to wait for new tasks when there are none
         * @throws IllegalArgumentException if a number is less than 1 or the pause isn't positive
         */
        public Settings {
            if (decodingThreads < 1 || batchSize < 1 || maxAttempts < 1 || !idlePause.isPositive()) {
                throw new IllegalArgumentException("Indexing settings out of range: decoding threads %d, batch size %d, attempts %d, pause %s"
                        .formatted(decodingThreads, batchSize, maxAttempts, idlePause));
            }
        }
    }

    private final EnrichmentQueue queue;
    private final PhotoRepository photos;
    private final FileTree fileTree;
    private final ImageDecoder decoder;
    private final EmbeddingModel model;
    private final EmbeddingRepository embeddings;
    private final EnricherVersion enricher;
    private final Clock clock;
    private final Settings settings;

    public Indexer(
            EnrichmentQueue queue,
            PhotoRepository photos,
            FileTree fileTree,
            ImageDecoder decoder,
            EmbeddingModel model,
            EmbeddingRepository embeddings,
            EnricherVersion enricher,
            Clock clock,
            Settings settings) {
        this.queue = queue;
        this.photos = photos;
        this.fileTree = fileTree;
        this.decoder = decoder;
        this.model = model;
        this.embeddings = embeddings;
        this.enricher = enricher;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Indexes until asked to stop: puts the queue in order, then takes tasks while any are due and
     * waits for new ones when none are. Tasks taken and not finished when it stops are released,
     * to be taken again.
     *
     * @param stopRequested polled between batches and while waiting
     */
    public void run(BooleanSupplier stopRequested) {
        QueueRecovery recovery = queue.recover(enricher, clock.instant());
        if (recovery.resumed() + recovery.requeued() + recovery.created() > 0) {
            log.info("Indexing queue: interrupted tasks resumed {}, of an older version queued again {}, added {}",
                    recovery.resumed(), recovery.requeued(), recovery.created());
        }
        QueueCounts counts = queue.counts(enricher);
        log.info("Indexing {}: to go {}, indexed {}, failed {}",
                enricher.id(), counts.pending(), counts.done(), counts.failed());
        ExecutorService decoding = Executors.newFixedThreadPool(settings.decodingThreads(),
                Thread.ofPlatform().name("kadr-decode-", 1).daemon().factory());
        Progress progress = new Progress();
        try {
            while (!stopRequested.getAsBoolean()) {
                Instant now = clock.instant();
                List<Long> chunk = queue.claim(enricher, settings.batchSize() * BATCHES_A_CHUNK, now.minus(RETRY_DELAY), now);
                if (chunk.isEmpty()) {
                    progress.idle();
                    pause(stopRequested);
                    continue;
                }
                progress.busy();
                try {
                    process(chunk, decoding, stopRequested, progress);
                } catch (RuntimeException e) {
                    log.error("Indexing failed; trying again in {} s", settings.idlePause().toSeconds(), e);
                    pause(stopRequested);
                }
            }
        } finally {
            decoding.shutdown();
            try {
                if (!decoding.awaitTermination(DECODING_STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    log.warn("Decoding didn't stop in {} s", DECODING_STOP_TIMEOUT.toSeconds());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Decodes the photos of a chunk on the decoding threads and embeds them in batches as they
     * come. Whatever isn't stored or failed when this returns is released.
     */
    private void process(List<Long> chunk, ExecutorService decoding, BooleanSupplier stopRequested, Progress progress) {
        Map<Long, List<Path>> files = photos.presentFiles(chunk);
        int minSide = model.minDecodedSide();
        BlockingQueue<Decoded> decoded = new ArrayBlockingQueue<>(2 * settings.batchSize());
        // Once set, the decoding threads pass over what is left, so the chunk drains quickly
        AtomicBoolean abandoned = new AtomicBoolean();
        for (long photoId : chunk) {
            decoding.execute(() -> {
                // Something is handed over whatever happens: this thread waits for every photo
                Decoded result;
                try {
                    result = abandoned.get()
                            ? new Skipped(photoId)
                            : decode(photoId, files.getOrDefault(photoId, List.of()), minSide);
                } catch (Throwable e) {
                    log.error("Decoding photo {} failed", photoId, e);
                    result = new Failed(new TaskFailure(photoId, "Decoding failed: " + e, true));
                }
                deliver(decoded, result);
            });
        }

        Set<Long> unfinished = new LinkedHashSet<>(chunk);
        List<Image> batch = new ArrayList<>();
        List<TaskFailure> failures = new ArrayList<>();
        RuntimeException failure = null;
        try {
            for (int received = 0; received < chunk.size(); received++) {
                switch (decoded.take()) {
                    case Image image -> batch.add(image);
                    case Failed failed -> failures.add(failed.failure());
                    case Skipped skipped -> {
                    }
                }
                boolean last = received == chunk.size() - 1;
                if (stopRequested.getAsBoolean() || failure != null) {
                    abandoned.set(true);
                    continue;
                }
                try {
                    if (batch.size() == settings.batchSize() || last && !batch.isEmpty()) {
                        embed(batch, unfinished, progress);
                        batch.clear();
                    }
                    if (failures.size() >= settings.batchSize() || last && !failures.isEmpty()) {
                        queue.fail(failures, enricher, settings.maxAttempts(), clock.instant());
                        failures.forEach(f -> unfinished.remove(f.photoId()));
                        progress.failed(failures.size());
                        failures.clear();
                    }
                } catch (RuntimeException e) {
                    // Drain the chunk first: the decoding threads wait for room in the queue
                    failure = e;
                    abandoned.set(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandoned.set(true);
        } finally {
            queue.release(unfinished, enricher, clock.instant());
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void embed(List<Image> batch, Set<Long> unfinished, Progress progress) {
        List<Long> photoIds = batch.stream().map(Image::photoId).toList();
        List<float[]> vectors;
        try {
            vectors = model.embedImages(batch.stream().map(Image::image).toList());
        } catch (RuntimeException e) {
            log.warn("The model failed on {} photos", batch.size(), e);
            List<TaskFailure> failures = photoIds.stream()
                    .map(id -> new TaskFailure(id, "The model failed: " + e.getMessage(), true))
                    .toList();
            queue.fail(failures, enricher, settings.maxAttempts(), clock.instant());
            photoIds.forEach(unfinished::remove);
            progress.failed(failures.size());
            return;
        }
        List<PhotoEmbedding> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            results.add(new PhotoEmbedding(photoIds.get(i), vectors.get(i)));
        }
        embeddings.save(results, model.id(), enricher, clock.instant());
        photoIds.forEach(unfinished::remove);
        progress.done(results.size());
    }

    /**
     * Reads a photo from the first of its files that can be read.
     */
    private Decoded decode(long photoId, List<Path> files, int minSide) {
        if (files.isEmpty()) {
            return new Failed(new TaskFailure(photoId, "None of the files of the photo is present", true));
        }
        String problem = "";
        for (Path file : files) {
            if (!fileTree.isReadable(file)) {
                problem = "Can't read " + file;
                continue;
            }
            try {
                return new Image(photoId, decoder.decode(file, minSide));
            } catch (UndecodableImageException e) {
                // The files of a photo have the same content: the others won't do either
                return new Failed(new TaskFailure(photoId, message(e), false));
            } catch (IOException e) {
                problem = "Can't read %s: %s".formatted(file, message(e));
            } catch (RuntimeException e) {
                log.warn("Decoding {} failed", file, e);
                problem = "Decoding %s failed: %s".formatted(file, message(e));
            }
        }
        return new Failed(new TaskFailure(photoId, problem, true));
    }

    private static void deliver(BlockingQueue<Decoded> decoded, Decoded result) {
        try {
            decoded.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pause(BooleanSupplier stopRequested) {
        long until = System.nanoTime() + settings.idlePause().toNanos();
        try {
            while (!stopRequested.getAsBoolean() && System.nanoTime() < until) {
                Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String message(Exception e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }

    /** What a decoding thread hands over. */
    private sealed interface Decoded permits Image, Failed, Skipped {
    }

    /**
     * @param photoId the photo
     * @param image   its pixels
     */
    private record Image(long photoId, RgbImage image) implements Decoded {
    }

    /**
     * @param failure why the photo couldn't be decoded
     */
    private record Failed(TaskFailure failure) implements Decoded {
    }

    /**
     * @param photoId a photo left for later: the work is stopping
     */
    private record Skipped(long photoId) implements Decoded {
    }

    /**
     * Logs the progress about once a minute while there is work, and when it runs out.
     */
    private final class Progress {

        private boolean busy;
        private Instant since = Instant.EPOCH;
        private long done;
        private long failed;

        void busy() {
            if (!busy) {
                busy = true;
                since = clock.instant();
                done = 0;
                failed = 0;
                log.info("Indexing started, photos to go: {}", queue.counts(enricher).pending());
            }
        }

        void done(int count) {
            done += count;
            report();
        }

        void failed(int count) {
            failed += count;
            report();
        }

        void idle() {
            if (busy) {
                busy = false;
                QueueCounts counts = queue.counts(enricher);
                log.info("Indexing finished: indexed {}, failed {}", counts.done(), counts.failed());
            }
        }

        private void report() {
            Instant now = clock.instant();
            Duration elapsed = Duration.between(since, now);
            if (elapsed.compareTo(PROGRESS_INTERVAL) < 0) {
                return;
            }
            QueueCounts counts = queue.counts(enricher);
            double rate = (done + failed) / (elapsed.toMillis() / 1000.0);
            long secondsLeft = rate > 0 ? Math.round(counts.pending() / rate) : 0;
            log.info("Indexing: to go {}, indexed {}, failed {}; {} photos/s, about {} left",
                    counts.pending(), counts.done(), counts.failed(), "%.2f".formatted(rate), duration(secondsLeft));
            since = now;
            done = 0;
            failed = 0;
        }

        private static String duration(long seconds) {
            return seconds >= 3600
                    ? "%d h %02d min".formatted(seconds / 3600, seconds % 3600 / 60)
                    : "%d min".formatted(Math.max(1, seconds / 60));
        }
    }
}
