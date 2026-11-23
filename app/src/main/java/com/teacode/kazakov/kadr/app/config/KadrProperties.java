package com.teacode.kazakov.kadr.app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under the {@code kadr} prefix.
 *
 * @param dataDir  directory for everything the service stores: the catalog database,
 *                 the vector index and the model files
 * @param model    the embedding model
 * @param indexing how photos are indexed
 */
@ConfigurationProperties("kadr")
@Validated
public record KadrProperties(@NotNull Path dataDir, @Valid @NotNull Model model, @Valid @NotNull Indexing indexing) {

    /**
     * @param id  model identifier, e.g. {@code siglip2-base-patch16-224}; photos are queued for indexing with it
     * @param dir the folder with the model files
     */
    public record Model(@NotBlank String id, @NotNull Path dir) {
    }

    /**
     * @param enabled          whether photos are indexed; scanning goes on either way
     * @param decodingThreads  threads decoding photos alongside the model
     * @param inferenceThreads threads the model computes with; 0 for one per physical core,
     *                         taken as half the processors: the second thread of a core only
     *                         gets in the way
     * @param batchSize        photos the model takes at once
     * @param maxAttempts      attempts a photo gets before it is marked failed
     * @param maxMegapixels    larger images aren't decoded
     * @param idlePause        how often to look for new photos when there are none
     */
    public record Indexing(
            boolean enabled,
            @Min(1) int decodingThreads,
            @Min(0) int inferenceThreads,
            @Min(1) int batchSize,
            @Min(1) int maxAttempts,
            @Min(1) int maxMegapixels,
            @NotNull Duration idlePause) {

        /**
         * {@return the threads the model computes with}
         */
        public int effectiveInferenceThreads() {
            return inferenceThreads > 0 ? inferenceThreads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
    }
}
