package com.teacode.kazakov.kadr.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.teacode.kazakov.kadr.onnx.SiglipModelFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The real model for the tests that run the service with it: from the folder in the
 * {@code kadr.model.dir} system property, by default where the service looks for it. Its files
 * aren't in the repository, and it takes gigabytes, so such a test is skipped without the files or
 * without the memory free.
 */
public final class RealModel {

    public static final Path FOLDER = Path.of(System.getProperty("kadr.model.dir",
            Path.of(System.getProperty("user.home"), ".kadr", "models", "siglip2-base-patch16-224").toString()));

    private RealModel() {
    }

    /**
     * Skips the test unless the model files are there and the memory is free.
     *
     * @param megabytes what the test takes with the model
     */
    public static void assumeAvailable(long megabytes) throws IOException {
        assumeTrue(SiglipModelFiles.in(FOLDER).missing().isEmpty(), () -> "The model files aren't in " + FOLDER
                + "; set kadr.model.dir to their folder to run this test");
        long available = Files.readAllLines(Path.of("/proc/meminfo")).stream()
                .filter(line -> line.startsWith("MemAvailable:"))
                .mapToLong(line -> Long.parseLong(line.replaceAll("\\D", "")) / 1024)
                .findFirst()
                .orElse(Long.MAX_VALUE);
        assumeTrue(available >= megabytes, () -> "The test takes %d MB of memory with the model, %d MB is free"
                .formatted(megabytes, available));
    }
}
