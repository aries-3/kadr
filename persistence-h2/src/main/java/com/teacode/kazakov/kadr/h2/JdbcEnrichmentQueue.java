package com.teacode.kazakov.kadr.h2;

import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.EnrichmentQueue;
import com.teacode.kazakov.kadr.domain.enrichment.QueueCounts;
import com.teacode.kazakov.kadr.domain.enrichment.QueueRecovery;
import com.teacode.kazakov.kadr.domain.enrichment.TaskFailure;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The task queue in the {@code enrichment} table.
 */
public final class JdbcEnrichmentQueue implements EnrichmentQueue {

    /** The length of the {@code error} column. */
    private static final int ERROR_LENGTH = 4000;

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final TransactionOperations tx;

    public JdbcEnrichmentQueue(JdbcTemplate jdbcTemplate, TransactionOperations tx) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        this.batch = jdbcTemplate;
        this.tx = tx;
    }

    @Override
    public QueueRecovery recover(EnricherVersion enricher, Instant now) {
        OffsetDateTime at = time(now);
        return Objects.requireNonNull(tx.execute(status -> {
            int resumed = jdbc.sql("""
                            UPDATE enrichment SET status = 'PENDING', updated_at = :now
                            WHERE enricher_id = :enricher AND status = 'RUNNING'
                            """)
                    .param("enricher", enricher.id())
                    .param("now", at)
                    .update();
            int requeued = jdbc.sql("""
                            UPDATE enrichment
                            SET status = 'PENDING', version = :version, attempts = 0, error = NULL, updated_at = :now
                            WHERE enricher_id = :enricher AND version <> :version
                            """)
                    .param("enricher", enricher.id())
                    .param("version", enricher.version())
                    .param("now", at)
                    .update();
            // Only photos that are still somewhere: a rescan adds the task when a file comes back
            int created = jdbc.sql("""
                            INSERT INTO enrichment (photo_id, enricher_id, version, status, attempts, updated_at)
                            SELECT p.id, :enricher, :version, 'PENDING', 0, :now FROM photo p
                            WHERE NOT EXISTS (SELECT 1 FROM enrichment e WHERE e.photo_id = p.id AND e.enricher_id = :enricher)
                              AND EXISTS (SELECT 1 FROM photo_file f WHERE f.photo_id = p.id AND NOT f.missing)
                            """)
                    .param("enricher", enricher.id())
                    .param("version", enricher.version())
                    .param("now", at)
                    .update();
            return new QueueRecovery(resumed, requeued, created);
        }));
    }

    @Override
    public List<Long> claim(EnricherVersion enricher, int limit, Instant retryBefore, Instant now) {
        return Objects.requireNonNull(tx.execute(status -> {
            List<Long> photoIds = jdbc.sql("""
                            SELECT photo_id FROM enrichment
                            WHERE enricher_id = :enricher AND status = 'PENDING'
                              AND (attempts = 0 OR updated_at < :retryBefore)
                            ORDER BY photo_id
                            FETCH FIRST :limit ROWS ONLY
                            """)
                    .param("enricher", enricher.id())
                    .param("retryBefore", time(retryBefore))
                    .param("limit", limit)
                    .query(Long.class)
                    .list();
            if (!photoIds.isEmpty()) {
                jdbc.sql("""
                                UPDATE enrichment SET status = 'RUNNING', updated_at = :now
                                WHERE enricher_id = :enricher AND photo_id IN (:ids)
                                """)
                        .param("enricher", enricher.id())
                        .param("now", time(now))
                        .param("ids", photoIds)
                        .update();
            }
            return photoIds;
        }));
    }

    @Override
    public void release(Collection<Long> photoIds, EnricherVersion enricher, Instant now) {
        if (photoIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE enrichment SET status = 'PENDING', updated_at = :now
                        WHERE enricher_id = :enricher AND photo_id IN (:ids) AND status = 'RUNNING'
                        """)
                .param("enricher", enricher.id())
                .param("now", time(now))
                .param("ids", photoIds)
                .update();
    }

    @Override
    public void fail(List<TaskFailure> failures, EnricherVersion enricher, int maxAttempts, Instant now) {
        if (failures.isEmpty()) {
            return;
        }
        OffsetDateTime at = time(now);
        // The expressions of SET see the row as it was, so attempts + 1 is the count with this one
        tx.executeWithoutResult(status -> batch.batchUpdate("""
                        UPDATE enrichment
                        SET attempts = attempts + 1, error = ?, updated_at = ?,
                            status = CASE WHEN ? AND attempts + 1 < ? THEN 'PENDING' ELSE 'FAILED' END
                        WHERE photo_id = ? AND enricher_id = ?
                        """,
                failures, failures.size(), (ps, failure) -> {
                    ps.setString(1, truncate(failure.error()));
                    ps.setObject(2, at);
                    ps.setBoolean(3, failure.retry());
                    ps.setInt(4, maxAttempts);
                    ps.setLong(5, failure.photoId());
                    ps.setString(6, enricher.id());
                }));
    }

    @Override
    public QueueCounts counts(EnricherVersion enricher) {
        return jdbc.sql("""
                        SELECT COUNT(CASE WHEN status IN ('PENDING', 'RUNNING') THEN 1 END) AS pending,
                               COUNT(CASE WHEN status = 'DONE' THEN 1 END) AS done,
                               COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed
                        FROM enrichment WHERE enricher_id = :enricher
                        """)
                .param("enricher", enricher.id())
                .query((rs, row) -> new QueueCounts(rs.getLong("pending"), rs.getLong("done"), rs.getLong("failed")))
                .single();
    }

    private static OffsetDateTime time(Instant instant) {
        return Objects.requireNonNull(SqlTime.toSql(instant));
    }

    private static String truncate(String error) {
        return error.length() <= ERROR_LENGTH ? error : error.substring(0, ERROR_LENGTH - 1) + "…";
    }
}
