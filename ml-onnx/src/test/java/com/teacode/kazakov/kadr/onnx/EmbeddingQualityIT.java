package com.teacode.kazakov.kadr.onnx;

import static com.teacode.kazakov.kadr.onnx.TestModel.similarity;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * An early look at the search quality on photos of your own, before the service can search: the
 * best five photos for each of your queries. Takes a folder with photos and {@code queries.txt},
 * one query a line, in any language; lines starting with {@code #} are comments:
 * <pre>
 * ./mvnw -pl ml-onnx verify -Dit.test=EmbeddingQualityIT -Dkadr.quality.photos=/path/to/photos
 * </pre>
 * It prints the results and checks nothing: whether they are right is for a person to judge.
 * The scores are cosine similarities; a text and a photo it describes score about 0.1–0.2 with
 * SigLIP, so what matters is the order.
 */
class EmbeddingQualityIT {

    private static final int BATCH = 16;
    private static final int BEST = 5;

    @Test
    void printTheBestPhotosForEachQuery() throws Exception {
        Path folder = RealPhotos.folder("kadr.quality.photos");
        Path queryFile = folder.resolve("queries.txt");
        assumeTrue(Files.isRegularFile(queryFile), () -> "Put the queries into " + queryFile);
        List<String> queries = Files.readAllLines(queryFile).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        List<Path> found = RealPhotos.list(folder, 1000);
        SiglipModel model = TestModel.get();

        ImageIoDecoder decoder = new ImageIoDecoder(ImageIoDecoder.DEFAULT_MAX_PIXELS);
        List<Path> photos = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        List<RgbImage> batch = new ArrayList<>();
        for (Path photo : found) {
            List<RgbImage> image = RealPhotos.decode(decoder, photo, TestModel.DECODING_FACTOR * model.imageSize());
            if (!image.isEmpty()) {
                photos.add(photo);
                batch.addAll(image);
            }
            if (batch.size() == BATCH) {
                vectors.addAll(model.embedImages(batch));
                batch.clear();
            }
        }
        vectors.addAll(model.embedImages(batch));
        assumeTrue(!photos.isEmpty(), () -> "No photos in " + folder);

        List<float[]> queryVectors = model.embedTexts(queries);
        System.out.printf("%n%d photos, %d queries%n", photos.size(), queries.size());
        for (int q = 0; q < queries.size(); q++) {
            float[] query = queryVectors.get(q);
            System.out.printf("%n%s%n", queries.get(q));
            IntStream.range(0, photos.size()).boxed()
                    .sorted(Comparator.comparingDouble((Integer p) -> similarity(query, vectors.get(p))).reversed())
                    .limit(BEST)
                    .forEach(p -> System.out.printf("  %.3f  %s%n",
                            similarity(query, vectors.get(p)), folder.relativize(photos.get(p))));
        }
        System.out.println();
    }
}
