package com.teacode.kazakov.kadr.server.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under the {@code kadr} prefix.
 *
 * @param dataDir directory for everything the service stores: the catalog database,
 *                the vector index and the model files
 */
@ConfigurationProperties("kadr")
@Validated
public record KadrProperties(@NotNull Path dataDir) {
}
