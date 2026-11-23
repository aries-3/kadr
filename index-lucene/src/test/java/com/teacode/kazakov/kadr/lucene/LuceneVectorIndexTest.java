package com.teacode.kazakov.kadr.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneVectorIndexTest {

    private static final String MODEL = "test";

    private final Directory directory = new ByteBuffersDirectory();
    private LuceneVectorIndex index;

    @AfterEach
    void close() throws IOException {
        if (index != null) {
            index.close();
        }
        directory.close();
    }

    @Test
    void findsTheNearestVectorsWithTheirCosine() throws IOException {
        index = open(MODEL, 3);
        index.upsert(List.of(
                embedding(1, 1, 0, 0),
                embedding(2, 0, 1, 0),
                embedding(3, 0.8f, 0.6f, 0),
                embedding(4, -1, 0, 0)), 4);
        index.commit();

        List<VectorHit> hits = index.search(new float[] {1, 0, 0}, 3);

        assertThat(hits).extracting(VectorHit::photoId).containsExactly(1L, 3L, 2L);
        assertThat(hits).extracting(VectorHit::score)
                .satisfiesExactly(
                        score -> assertThat(score).isCloseTo(1.0, within(1e-6)),
                        score -> assertThat(score).isCloseTo(0.8, within(1e-6)),
                        score -> assertThat(score).isCloseTo(0.0, within(1e-6)));
    }

    @Test
    void aPhotoHasOneVectorTheLastOne() throws IOException {
        index = open(MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 1);
        index.upsert(List.of(embedding(1, 0, 1)), 2);
        index.commit();

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(new float[] {0, 1}, 5)).singleElement()
                .satisfies(hit -> assertThat(hit.score()).isCloseTo(1.0, within(1e-6)));
    }

    @Test
    void aCommitKeepsTheVectorsWithTheNumberOfTheLastWrite() throws IOException {
        index = open(MODEL, 2);
        assertThat(index.durableSeq()).isZero();
        index.upsert(List.of(embedding(1, 1, 0), embedding(2, 0, 1)), 5);
        index.commit();
        index.close();

        index = open(MODEL, 2);

        assertThat(index.durableSeq()).isEqualTo(5);
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.search(new float[] {0, 1}, 1)).extracting(VectorHit::photoId).containsExactly(2L);
    }

    @Test
    void closingCommitsWhatWasAdded() throws IOException {
        index = open(MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 3);
        index.close();

        index = open(MODEL, 2);

        assertThat(index.durableSeq()).isEqualTo(3);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void aCrashLosesWhatWasNotCommitted() throws IOException {
        index = open(MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 5);
        index.commit();
        index.upsert(List.of(embedding(2, 0, 1)), 9);
        index.abandon();

        index = open(MODEL, 2);

        assertThat(index.durableSeq()).as("what to take again from the catalog").isEqualTo(5);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void anIndexOfAnotherModelOrLengthIsMadeAnew() throws IOException {
        index = open(MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 5);
        index.close();

        index = open("another", 2);
        assertThat(index.durableSeq()).isZero();
        assertThat(index.size()).isZero();
        index.close();

        index = open(MODEL, 3);
        assertThat(index.durableSeq()).isZero();
        assertThat(index.size()).isZero();
    }

    @Test
    void anIndexThatCanNotBeReadIsMadeAnew(@TempDir Path folder) throws IOException {
        index = LuceneVectorIndex.open(folder, MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 5);
        index.close();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(file -> file.getFileName().toString().startsWith("segments_")).toList()) {
                Files.write(file, new byte[] {1, 2, 3});
            }
        }

        index = LuceneVectorIndex.open(folder, MODEL, 2);

        assertThat(index.durableSeq()).isZero();
        assertThat(index.size()).isZero();
        index.upsert(List.of(embedding(1, 1, 0)), 1);
        index.commit();
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void theIndexOfAnotherProcessIsLeftAlone(@TempDir Path folder) throws IOException {
        index = LuceneVectorIndex.open(folder, MODEL, 2);
        index.upsert(List.of(embedding(1, 1, 0)), 5);
        index.commit();

        assertThatExceptionOfType(LockObtainFailedException.class)
                .isThrownBy(() -> LuceneVectorIndex.open(folder, "another", 2));
        assertThat(index.search(new float[] {1, 0}, 1)).hasSize(1);
    }

    @Test
    void aVectorOfAnotherLengthIsRefused() throws IOException {
        index = open(MODEL, 2);

        assertThatIllegalArgumentException().isThrownBy(() -> index.upsert(List.of(embedding(1, 1, 0, 0)), 1));
    }

    @Test
    void anEmptyIndexFindsNothing() throws IOException {
        index = open(MODEL, 2);

        assertThat(index.search(new float[] {1, 0}, 10)).isEmpty();
    }

    private LuceneVectorIndex open(String model, int dimension) throws IOException {
        return LuceneVectorIndex.open(directory, false, model, dimension);
    }

    private static PhotoEmbedding embedding(long photoId, float... vector) {
        return new PhotoEmbedding(photoId, vector);
    }
}
