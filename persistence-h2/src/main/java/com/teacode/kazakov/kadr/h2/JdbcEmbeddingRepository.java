package com.teacode.kazakov.kadr.h2;

import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.StoredEmbedding;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Vectors in the {@code embedding} table, as float32 little-endian, numbered by
 * {@code embedding_seq}.
 */
public final class JdbcEmbeddingRepository implements EmbeddingRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final TransactionOperations tx;

    public JdbcEmbeddingRepository(JdbcTemplate jdbcTemplate, TransactionOperations tx) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        this.batch = jdbcTemplate;
        this.tx = tx;
    }

    @Override
    public void save(List<PhotoEmbedding> embeddings, String modelId, EnricherVersion enricher, Instant now) {
        if (embeddings.isEmpty()) {
            return;
        }
        OffsetDateTime at = Objects.requireNonNull(SqlTime.toSql(now));
        tx.executeWithoutResult(status -> {
            batch.batchUpdate("""
                            MERGE INTO embedding (photo_id, model_id, vector, seq, created_at)
                            KEY (photo_id, model_id) VALUES (?, ?, ?, NEXT VALUE FOR embedding_seq, ?)
                            """,
                    embeddings, embeddings.size(), (ps, embedding) -> {
                        ps.setLong(1, embedding.photoId());
                        ps.setString(2, modelId);
                        ps.setBytes(3, bytes(embedding.vector()));
                        ps.setObject(4, at);
                    });
            batch.batchUpdate("""
                            UPDATE enrichment SET status = 'DONE', error = NULL, updated_at = ?
                            WHERE photo_id = ? AND enricher_id = ?
                            """,
                    embeddings, embeddings.size(), (ps, embedding) -> {
                        ps.setObject(1, at);
                        ps.setLong(2, embedding.photoId());
                        ps.setString(3, enricher.id());
                    });
        });
    }

    @Override
    public List<StoredEmbedding> after(String modelId, long seq, int limit) {
        return jdbc.sql("""
                        SELECT photo_id, vector, seq FROM embedding
                        WHERE model_id = :model AND seq > :seq
                        ORDER BY seq
                        FETCH FIRST :limit ROWS ONLY
                        """)
                .param("model", modelId)
                .param("seq", seq)
                .param("limit", limit)
                .query((rs, row) -> new StoredEmbedding(
                        new PhotoEmbedding(rs.getLong("photo_id"), floats(rs.getBytes("vector"))), rs.getLong("seq")))
                .list();
    }

    @Override
    public long countAfter(String modelId, long seq) {
        return jdbc.sql("SELECT COUNT(*) FROM embedding WHERE model_id = :model AND seq > :seq")
                .param("model", modelId)
                .param("seq", seq)
                .query(Long.class)
                .single();
    }

    static float[] floats(byte[] bytes) {
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    static byte[] bytes(float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(vector);
        return bytes.array();
    }
}
