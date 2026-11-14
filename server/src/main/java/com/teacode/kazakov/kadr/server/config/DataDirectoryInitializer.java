package com.teacode.kazakov.kadr.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Creates the data directory before any bean is created.
 * <p>
 * The catalog database, the vector index and the models all live there, so it must exist
 * before the data source opens. Failing here gives a clear message instead of a database error.
 * Registered in {@code META-INF/spring.factories}.
 */
public class DataDirectoryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Path dataDir = Binder.get(context.getEnvironment())
                .bind("kadr.data-dir", Path.class)
                .orElseThrow(() -> new IllegalStateException("Property 'kadr.data-dir' is not set"))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't create the data directory " + dataDir, e);
        }
        log.info("Data directory: {}", dataDir);
    }
}
