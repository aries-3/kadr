package com.teacode.kazakov.kadr.onnx;

import static com.teacode.kazakov.kadr.onnx.TestModel.similarity;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * How fast photos are decoded and embedded on this machine: to choose the indexing settings
 * (decoding threads, inference threads, batch size) and to estimate how long indexing an archive
 * takes. Runs on real photos, which aren't in the repository, so only when given a folder:
 * <pre>
 * ./mvnw -pl ml-onnx verify -Dit.test=EmbeddingThroughputIT -Dkadr.benchmark.photos=/path/to/photos
 * </pre>
 * Also {@code kadr.benchmark.count}: how many photos to take, 32 by default;
 * {@code kadr.benchmark.threads}: the inference threads to try, {@code 1,2,4} by default;
 * {@code kadr.benchmark.batches}: the batch sizes to try, {@code 1,8,16,32} by default.
 * <p>
 * It prints what it measures and checks nothing. It takes minutes: every setting runs over all the
 * photos.
 */
class EmbeddingThroughputIT {

    /** Decoded keeping four times the model's input size, as the indexing will: see {@link #reducedDecoding}. */
    private static final int MIN_SIDE = TestModel.DECODING_FACTOR * 224;
    private static final int PIPELINE_BATCH = 16;
    private static final List<String> QUERIES = List.of(
            "a dog on the beach", "собака на пляже", "mountains at sunset", "горы на закате",
            "a birthday cake with candles", "торт со свечами", "a receipt", "чек из магазина",
            "children playing in the snow", "дети играют в снегу");

    private final int cores = Runtime.getRuntime().availableProcessors();
    private final ImageIoDecoder decoder = new ImageIoDecoder(ImageIoDecoder.DEFAULT_MAX_PIXELS);

    @Test
    void measure() throws Exception {
        Path folder = RealPhotos.folder("kadr.benchmark.photos");
        List<Path> found = RealPhotos.list(folder, Integer.getInteger("kadr.benchmark.count", 32));
        assumeTrue(!found.isEmpty(), () -> "No photos in " + folder);
        SiglipModelFiles files = TestModel.files();

        System.out.printf("%nCPU: %s, %d cores%n", cpu(), cores);

        // Decoding, one thread: also leaves out what can't be decoded
        List<Path> photos = new ArrayList<>();
        List<RgbImage> images = new ArrayList<>();
        long bytes = 0;
        long start = System.nanoTime();
        for (Path photo : found) {
            List<RgbImage> image = RealPhotos.decode(decoder, photo, MIN_SIDE);
            if (!image.isEmpty()) {
                photos.add(photo);
                images.add(image.getFirst());
                bytes += Files.size(photo);
            }
        }
        assumeTrue(!photos.isEmpty(), "No photo could be decoded");
        double seconds = seconds(start);
        System.out.printf("%d photos, %.1f MB on average%n%n", photos.size(), bytes / 1e6 / photos.size());
        System.out.printf("Decoding:%n  1 thread:  %5.0f ms a photo, %5.1f photos/s%n",
                seconds * 1000 / photos.size(), photos.size() / seconds);
        for (int threads = 2; threads <= cores; threads *= 2) {
            System.out.printf("  %d threads: %26.1f photos/s%n", threads, decodeInParallel(photos, threads));
        }

        double best = 0;
        String bestSetting = "";
        for (int threads : sizes("kadr.benchmark.threads", "1,2,4")) {
            if (threads > cores) {
                continue;
            }
            try (SiglipModel model = SiglipModel.load(TestModel.ID, files, threads)) {
                model.embedImages(images.subList(0, 1));
                System.out.printf("%nInference, %d thread%s:%n", threads, threads == 1 ? "" : "s");
                for (int batch : sizes("kadr.benchmark.batches", "1,8,16,32")) {
                    start = System.nanoTime();
                    for (int from = 0; from < images.size(); from += batch) {
                        model.embedImages(images.subList(from, Math.min(images.size(), from + batch)));
                    }
                    seconds = seconds(start);
                    System.out.printf("  batch %2d:   %5.0f ms a photo, %5.2f photos/s%n",
                            batch, seconds * 1000 / images.size(), images.size() / seconds);
                }
                System.out.printf("  a search query: %s%n", queryTimes(model));
                for (int decoding = 1; decoding <= 2; decoding++) {
                    double rate = pipeline(model, photos, decoding);
                    System.out.printf("  decoding in %d thread%s alongside, batch %d: %.2f photos/s%n",
                            decoding, decoding == 1 ? "" : "s", PIPELINE_BATCH, rate);
                    if (rate > best) {
                        best = rate;
                        bestSetting = "%d inference threads, %d decoding, batch %d".formatted(threads, decoding, PIPELINE_BATCH);
                    }
                }
            }
        }

        System.out.printf("%nBest: %.2f photos/s with %s%n", best, bestSetting);
        for (int archive : new int[] {10_000, 100_000}) {
            System.out.printf("  %,d photos: %.1f hours%n", archive, archive / best / 3600);
        }
        System.out.printf("Peak memory: %d MB%n%n", TestModel.peakMemoryMegabytes());
    }

    private double decodeInParallel(List<Path> photos, int threads) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<List<RgbImage>>> tasks = photos.stream()
                    .<Callable<List<RgbImage>>>map(photo -> () -> RealPhotos.decode(decoder, photo, MIN_SIDE))
                    .toList();
            long start = System.nanoTime();
            for (var result : pool.invokeAll(tasks)) {
                result.get();
            }
            return photos.size() / seconds(start);
        }
    }

    /**
     * As the indexing will run: photos decoded in other threads, embedded in batches as they come.
     */
    private double pipeline(SiglipModel model, List<Path> photos, int decodingThreads) throws Exception {
        BlockingQueue<RgbImage> decoded = new ArrayBlockingQueue<>(2 * PIPELINE_BATCH);
        try (ExecutorService pool = Executors.newFixedThreadPool(decodingThreads)) {
            long start = System.nanoTime();
            for (Path photo : photos) {
                pool.submit(() -> {
                    decoded.put(decoder.decode(photo, MIN_SIDE));
                    return null;
                });
            }
            List<RgbImage> batch = new ArrayList<>();
            for (int done = 0; done < photos.size(); ) {
                RgbImage image = decoded.poll(1, TimeUnit.MINUTES);
                if (image == null) {
                    throw new IllegalStateException("Decoding stopped");
                }
                batch.add(image);
                if (batch.size() == PIPELINE_BATCH || done + batch.size() == photos.size()) {
                    model.embedImages(batch);
                    done += batch.size();
                    batch.clear();
                }
            }
            return photos.size() / seconds(start);
        }
    }

    private static String queryTimes(SiglipModel model) {
        model.embedTexts(List.of("warm up"));
        long[] millis = new long[QUERIES.size()];
        for (int i = 0; i < QUERIES.size(); i++) {
            long start = System.nanoTime();
            model.embedTexts(List.of(QUERIES.get(i)));
            millis[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(millis);
        return "median %d ms, slowest %d ms".formatted(millis[millis.length / 2], millis[millis.length - 1]);
    }

    /**
     * Decoding reads every n-th pixel of a large photo, which keeps less detail than scaling the
     * whole photo down: how much that changes the vectors, and what decoding takes, for several
     * shortest sides to keep. Runs alone with {@code -Dit.test=EmbeddingThroughputIT#reducedDecoding}.
     */
    @Test
    void reducedDecoding() throws Exception {
        Path folder = RealPhotos.folder("kadr.benchmark.photos");
        List<Path> photos = RealPhotos.list(folder, Integer.getInteger("kadr.benchmark.count", 32));
        assumeTrue(!photos.isEmpty(), () -> "No photos in " + folder);
        SiglipModel model = TestModel.get();
        int[] factors = {2, 3, 4};
        double[] lowest = {1, 1, 1};
        double[] sum = new double[factors.length];
        long[] nanos = new long[factors.length];
        int count = 0;
        for (Path photo : photos) {
            List<RgbImage> whole = RealPhotos.decode(decoder, photo, Integer.MAX_VALUE);
            if (whole.isEmpty()) {
                continue;
            }
            float[] reference = model.embedImages(whole).getFirst();
            for (int i = 0; i < factors.length; i++) {
                long start = System.nanoTime();
                RgbImage reduced = decoder.decode(photo, factors[i] * model.imageSize());
                nanos[i] += System.nanoTime() - start;
                double similarity = similarity(model.embedImages(List.of(reduced)).getFirst(), reference);
                lowest[i] = Math.min(lowest[i], similarity);
                sum[i] += similarity;
            }
            count++;
        }
        System.out.printf("%nDecoding reduced against whole, %d photos: similarity of the vectors%n", count);
        for (int i = 0; i < factors.length; i++) {
            System.out.printf("  shorter side at least %d × %d px: lowest %.4f, mean %.4f; decoding %.0f ms a photo%n",
                    factors[i], model.imageSize(), lowest[i], sum[i] / count, nanos[i] / 1e6 / count);
        }
        System.out.println();
    }

    private static int[] sizes(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static String cpu() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }
}
