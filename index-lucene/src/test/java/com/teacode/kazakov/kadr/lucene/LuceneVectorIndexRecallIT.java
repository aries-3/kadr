package com.teacode.kazakov.kadr.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

/**
 * The HNSW search is approximate: how many of the true ten nearest it finds, against comparing the
 * query with every vector. Asking for 100 and taking the ten best, as the search of the service
 * does.
 * <p>
 * The vectors come in groups, as those of photos do: similar photos lie close together. Vectors
 * spread evenly over the sphere are the worst case for HNSW, every one almost as far as any other:
 * on those it finds 0.89 of the ten nearest.
 */
class LuceneVectorIndexRecallIT {

    private static final int VECTORS = 10_000;
    private static final int DIMENSION = 768;
    private static final int QUERIES = 50;
    private static final int TOP = 10;
    private static final int ASKED = 100;
    private static final int GROUPS = 100;
    /** Noise of each value: a vector keeps a cosine of about 0.75 to the middle of its group. */
    private static final double SPREAD = 0.032;

    @Test
    void findsAlmostAllOfTheTrueNearest() throws IOException {
        Random random = new Random(42);
        List<float[]> groups = new ArrayList<>(GROUPS);
        for (int g = 0; g < GROUPS; g++) {
            groups.add(unit(random));
        }
        List<PhotoEmbedding> embeddings = new ArrayList<>(VECTORS);
        for (int i = 0; i < VECTORS; i++) {
            embeddings.add(new PhotoEmbedding(i, near(groups.get(i % GROUPS), random)));
        }

        int found = 0;
        try (ByteBuffersDirectory directory = new ByteBuffersDirectory();
                LuceneVectorIndex index = LuceneVectorIndex.open(directory, false, "test", DIMENSION)) {
            index.upsert(embeddings, VECTORS);
            index.commit();
            for (int q = 0; q < QUERIES; q++) {
                // Another vector of a group, as a query is near the photos it finds
                float[] query = near(groups.get(random.nextInt(GROUPS)), random);
                Set<Long> approximate = new HashSet<>();
                index.search(query, ASKED).stream().limit(TOP).map(VectorHit::photoId).forEach(approximate::add);
                found += (int) exact(embeddings, query).stream().filter(approximate::contains).count();
            }
        }

        double recall = (double) found / (QUERIES * TOP);
        System.out.printf("Recall of the ten nearest: %.3f%n", recall);
        assertThat(recall).isGreaterThanOrEqualTo(0.95);
    }

    private static List<Long> exact(List<PhotoEmbedding> embeddings, float[] query) {
        return IntStream.range(0, embeddings.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> -dot(embeddings.get(i).vector(), query)))
                .limit(TOP)
                .map(i -> embeddings.get(i).photoId())
                .toList();
    }

    private static float[] unit(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return normalized(vector);
    }

    private static float[] near(float[] vector, Random random) {
        float[] near = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            near[i] = vector[i] + (float) (random.nextGaussian() * SPREAD);
        }
        return normalized(near);
    }

    private static float[] normalized(float[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) length;
        }
        return vector;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
