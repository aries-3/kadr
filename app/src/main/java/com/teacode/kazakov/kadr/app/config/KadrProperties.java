package com.teacode.kazakov.kadr.app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under the {@code kadr} prefix.
 *
 * @param dataDir directory for everything the service stores: the catalog database,
 *                the vector index and the model files
 * @param model   the embedding model
 */
@ConfigurationProperties("kadr")
@Validated
public record KadrProperties(@NotNull Path dataDir, @Valid @NotNull Model model) {

    /**
     * @param id model identifier, e.g. {@code siglip2-base-patch16-224}; photos are queued for indexing with it
     */
    public record Model(@NotBlank String id) {
    }
}
