package com.teacode.kazakov.kadr.onnx;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * The real model for the integration tests, loaded once for all of them: from the folder in the
 * {@code kadr.model.dir} system property, by default where the service looks for it.
 * <p>
 * The tests are skipped without the model files, which aren't in the repository, and when there
 * isn't enough free memory to load the model: on a busy machine the system would rather kill other
 * programs than this test.
 */
final class TestModel {

    static final String ID = "siglip2-base-patch16-224";

    /**
     * Photos are decoded keeping the shorter side at least this many times the model's input, as
     * {@link com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel#minDecodedSide()} says: for
     * the measurements that pick their sizes before the model is loaded.
     */
    static final int DECODING_FACTOR = 4;

    static final Path FOLDER = Path.of(System.getProperty("kadr.model.dir",
            Path.of(System.getProperty("user.home"), ".kadr", "models", ID).toString()));

    /** What loading the base model takes at the peak, about 2.1 GB, with room for the tests. */
    private static final long MEMORY_NEEDED = 5L << 29;

    private static @Nullable SiglipModel model;

    private TestModel() {
    }

    static synchronized SiglipModel get() {
        if (model == null) {
            model = SiglipModel.load(ID, files(), Runtime.getRuntime().availableProcessors());
        }
        return model;
    }

    /**
     * The model files, if they are there and there is memory enough to load them; otherwise the
     * test is skipped.
     */
    static SiglipModelFiles files() {
        SiglipModelFiles files = SiglipModelFiles.in(FOLDER);
        assumeTrue(files.missing().isEmpty(), () -> "The model files aren't in " + FOLDER
                + "; set kadr.model.dir to their folder to run this test");
        OptionalLong available = availableMemory();
        assumeTrue(available.isEmpty() || available.getAsLong() >= MEMORY_NEEDED, () ->
                "Loading the model takes %.1f GB of memory, %.1f GB is free: close other programs to run this test"
                        .formatted(gigabytes(MEMORY_NEEDED), gigabytes(available.orElseThrow())));
        return files;
    }

    /**
     * {@return the most memory this process has taken so far, in MB, by Linux; -1 elsewhere}
     */
    static long peakMemoryMegabytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Not Linux: unknown
        }
        return -1;
    }

    /**
     * The cosine similarity of unit vectors.
     */
    static double similarity(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * Memory the system can give without swapping, by Linux; unknown elsewhere.
     */
    private static OptionalLong availableMemory() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return OptionalLong.of(Long.parseLong(line.replaceAll("\\D", "")) * 1024);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Not Linux: unknown
        }
        return OptionalLong.empty();
    }

    private static double gigabytes(long bytes) {
        return bytes / (double) (1L << 30);
    }
}
