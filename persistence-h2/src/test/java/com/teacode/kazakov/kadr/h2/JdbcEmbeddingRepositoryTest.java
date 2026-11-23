package com.teacode.kazakov.kadr.h2;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.catalog.ScannedFile;
import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.StoredEmbedding;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.QueueCounts;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEmbeddingRepositoryTest {

    private static final EnricherVersion ENRICHER = new EnricherVersion("embedding:test", 1);
    private static final String MODEL = "test";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient sql = JdbcClient.create(db.jdbcTemplate);
    private final JdbcPhotoRepository photos = new JdbcPhotoRepository(db.jdbcTemplate, db.transactions);
    private final JdbcEnrichmentQueue queue = new JdbcEnrichmentQueue(db.jdbcTemplate, db.transactions);
    private final JdbcEmbeddingRepository embeddings = new JdbcEmbeddingRepository(db.jdbcTemplate, db.transactions);

    @Test
    void storesTheVectorsAndFinishesTheirTasks() {
        long a = photo(1);
        long b = photo(2);
        queue.claim(ENRICHER, 2, NOW, NOW);

        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {0.6f, 0.8f}), new PhotoEmbedding(b, new float[] {1, 0})),
                MODEL, ENRICHER, NOW);

        assertThat(vector(a)).containsExactly(0.6f, 0.8f);
        assertThat(vector(b)).containsExactly(1f, 0f);
        assertThat(queue.counts(ENRICHER)).isEqualTo(new QueueCounts(0, 2, 0));
    }

    @Test
    void theVectorIsFloat32LittleEndian() {
        long a = photo(1);

        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {1.5f})), MODEL, ENRICHER, NOW);

        byte[] stored = sql.sql("SELECT vector FROM embedding").query(byte[].class).single();
        assertThat(stored).containsExactly(0x00, 0x00, 0xC0, 0x3F);
    }

    @Test
    void everyWriteIsNumberedAfterThePrevious() {
        long a = photo(1);
        long b = photo(2);
        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {1})), MODEL, ENRICHER, NOW);
        embeddings.save(List.of(new PhotoEmbedding(b, new float[] {1})), MODEL, ENRICHER, NOW);
        long first = seq(a);

        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {0.5f})), MODEL, ENRICHER, NOW);

        assertThat(seq(b)).isGreaterThan(first);
        assertThat(seq(a)).as("rewritten").isGreaterThan(seq(b));
        assertThat(vector(a)).containsExactly(0.5f);
        assertThat(sql.sql("SELECT COUNT(*) FROM embedding").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void readsTheVectorsWrittenAfterAGivenWriteInTheirOrder() {
        long a = photo(1);
        long b = photo(2);
        long c = photo(3);
        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {1, 0}), new PhotoEmbedding(b, new float[] {0, 1})),
                MODEL, ENRICHER, NOW);
        embeddings.save(List.of(new PhotoEmbedding(c, new float[] {0.6f, 0.8f})), MODEL, ENRICHER, NOW);
        embeddings.save(List.of(new PhotoEmbedding(a, new float[] {0.8f, 0.6f})), MODEL, ENRICHER, NOW);
        embeddings.save(List.of(new PhotoEmbedding(b, new float[] {1})), "another", ENRICHER, NOW);

        List<StoredEmbedding> all = embeddings.after(MODEL, 0, 10);
        List<StoredEmbedding> rest = embeddings.after(MODEL, all.get(0).seq(), 2);

        assertThat(all).extracting(stored -> stored.embedding().photoId())
                .as("a rewritten vector comes again, at its new place")
                .containsExactly(b, c, a);
        assertThat(all).extracting(StoredEmbedding::seq).isSorted();
        assertThat(all.get(2).embedding().vector()).containsExactly(0.8f, 0.6f);
        assertThat(rest).extracting(stored -> stored.embedding().photoId()).containsExactly(c, a);
        assertThat(embeddings.countAfter(MODEL, 0)).isEqualTo(3);
        assertThat(embeddings.countAfter(MODEL, all.get(2).seq())).isZero();
    }

    private long photo(int content) {
        String path = "/photos/%d.jpg".formatted(content);
        photos.record(List.of(new ScannedFile(path, 100, 1000, new ContentHash("%064x".formatted(content)))), ENRICHER, NOW);
        return sql.sql("SELECT photo_id FROM photo_file WHERE path = :path").param("path", path).query(Long.class).single();
    }

    private float[] vector(long photo) {
        byte[] bytes = sql.sql("SELECT vector FROM embedding WHERE photo_id = :id AND model_id = :model")
                .param("id", photo).param("model", MODEL).query(byte[].class).single();
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    private long seq(long photo) {
        return sql.sql("SELECT seq FROM embedding WHERE photo_id = :id").param("id", photo).query(Long.class).single();
    }
}
