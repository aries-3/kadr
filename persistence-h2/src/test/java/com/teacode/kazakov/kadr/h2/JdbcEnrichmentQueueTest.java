package com.teacode.kazakov.kadr.h2;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.catalog.ScannedFile;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.QueueCounts;
import com.teacode.kazakov.kadr.domain.enrichment.QueueRecovery;
import com.teacode.kazakov.kadr.domain.enrichment.TaskFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEnrichmentQueueTest {

    private static final EnricherVersion ENRICHER = new EnricherVersion("embedding:test", 2);
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofMinutes(5));
    private static final int MAX_ATTEMPTS = 3;

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient sql = JdbcClient.create(db.jdbcTemplate);
    private final JdbcPhotoRepository photos = new JdbcPhotoRepository(db.jdbcTemplate, db.transactions);
    private final JdbcEnrichmentQueue queue = new JdbcEnrichmentQueue(db.jdbcTemplate, db.transactions);

    @Test
    void takesPendingTasksInTheOrderPhotosWereFound() {
        List<Long> ids = photos(5);

        List<Long> first = queue.claim(ENRICHER, 3, NOW, NOW);
        List<Long> rest = queue.claim(ENRICHER, 3, NOW, NOW);

        assertThat(first).containsExactlyElementsOf(ids.subList(0, 3));
        assertThat(rest).containsExactlyElementsOf(ids.subList(3, 5));
        assertThat(queue.claim(ENRICHER, 3, NOW, NOW)).isEmpty();
        assertThat(statuses()).containsOnly("RUNNING");
        assertThat(queue.counts(ENRICHER)).isEqualTo(new QueueCounts(5, 0, 0));
    }

    @Test
    void releasedTasksArePendingWithoutAnAttempt() {
        List<Long> ids = photos(2);
        queue.claim(ENRICHER, 2, NOW, NOW);

        queue.release(ids, ENRICHER, NOW);

        assertThat(statuses()).containsOnly("PENDING");
        assertThat(sql.sql("SELECT attempts FROM enrichment").query(Integer.class).list()).containsOnly(0);
        assertThat(queue.claim(ENRICHER, 2, NOW, NOW)).containsExactlyElementsOf(ids);
    }

    @Test
    void aTaskThatMayBeRetriedWaitsAndComesBackUntilItsAttemptsAreUsedUp() {
        long photo = photos(1).getFirst();

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            queue.claim(ENRICHER, 1, NOW, NOW);
            queue.fail(List.of(new TaskFailure(photo, "Can't read the file", true)), ENRICHER, MAX_ATTEMPTS, NOW);
            assertThat(status(photo)).isEqualTo("PENDING");
            assertThat(queue.claim(ENRICHER, 1, NOW, NOW)).as("not before it has waited").isEmpty();
            assertThat(queue.claim(ENRICHER, 1, LATER, LATER)).containsExactly(photo);
            queue.release(List.of(photo), ENRICHER, NOW);
        }
        queue.claim(ENRICHER, 1, LATER, LATER);
        queue.fail(List.of(new TaskFailure(photo, "Can't read the file", true)), ENRICHER, MAX_ATTEMPTS, NOW);

        assertThat(status(photo)).isEqualTo("FAILED");
        assertThat(error(photo)).isEqualTo("Can't read the file");
        assertThat(queue.counts(ENRICHER)).isEqualTo(new QueueCounts(0, 0, 1));
    }

    @Test
    void aTaskThatCanNotSucceedFailsAtOnce() {
        long photo = photos(1).getFirst();
        queue.claim(ENRICHER, 1, NOW, NOW);

        queue.fail(List.of(new TaskFailure(photo, "Not a JPEG, PNG or WebP image", false)), ENRICHER, MAX_ATTEMPTS, NOW);

        assertThat(status(photo)).isEqualTo("FAILED");
        assertThat(error(photo)).isEqualTo("Not a JPEG, PNG or WebP image");
    }

    @Test
    void aLongErrorIsCut() {
        long photo = photos(1).getFirst();

        queue.fail(List.of(new TaskFailure(photo, "x".repeat(5000), false)), ENRICHER, MAX_ATTEMPTS, NOW);

        assertThat(error(photo)).hasSize(4000).endsWith("x…");
    }

    @Test
    void recoveryResumesTasksLeftRunning() {
        List<Long> ids = photos(3);
        queue.claim(ENRICHER, 2, NOW, NOW);

        QueueRecovery recovery = queue.recover(ENRICHER, NOW);

        assertThat(recovery).isEqualTo(new QueueRecovery(2, 0, 0));
        assertThat(statuses()).containsOnly("PENDING");
        assertThat(queue.claim(ENRICHER, 3, NOW, NOW)).containsExactlyElementsOf(ids);
    }

    @Test
    void recoveryRequeuesTasksOfAnotherVersion() {
        List<Long> ids = photos(3);
        sql.sql("UPDATE enrichment SET status = 'DONE' WHERE photo_id = :id").param("id", ids.get(0)).update();
        sql.sql("UPDATE enrichment SET status = 'FAILED', attempts = 3, error = 'broken' WHERE photo_id = :id")
                .param("id", ids.get(1)).update();
        EnricherVersion next = new EnricherVersion(ENRICHER.id(), ENRICHER.version() + 1);

        QueueRecovery recovery = queue.recover(next, NOW);

        assertThat(recovery).isEqualTo(new QueueRecovery(0, 3, 0));
        assertThat(statuses()).containsOnly("PENDING");
        assertThat(sql.sql("SELECT DISTINCT version FROM enrichment").query(Integer.class).list())
                .containsExactly(next.version());
        assertThat(sql.sql("SELECT attempts FROM enrichment").query(Integer.class).list()).containsOnly(0);
        assertThat(error(ids.get(1))).isNull();
    }

    @Test
    void recoveryGivesTasksToPhotosOfAnotherEnricherThatAreStillThere() {
        List<Long> ids = photos(3);
        photos.markMissing(sql.sql("SELECT id FROM photo_file WHERE photo_id = :id")
                .param("id", ids.get(2)).query(Long.class).list());
        EnricherVersion other = new EnricherVersion("embedding:other", 1);

        QueueRecovery recovery = queue.recover(other, NOW);

        assertThat(recovery).isEqualTo(new QueueRecovery(0, 0, 2));
        assertThat(queue.claim(other, 5, NOW, NOW)).containsExactlyElementsOf(ids.subList(0, 2));
        assertThat(queue.counts(ENRICHER)).as("the tasks of the first are untouched").isEqualTo(new QueueCounts(3, 0, 0));
    }

    /**
     * Photos with a pending task each, as a scan leaves them.
     *
     * @return their ids, in the order they were found
     */
    private List<Long> photos(int count) {
        photos.record(IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ScannedFile("/photos/%03d.jpg".formatted(i), 100, 1000,
                        new ContentHash("%064x".formatted(i))))
                .toList(), ENRICHER, NOW);
        return sql.sql("SELECT id FROM photo ORDER BY id").query(Long.class).list();
    }

    private List<String> statuses() {
        return sql.sql("SELECT status FROM enrichment").query(String.class).list();
    }

    private String status(long photo) {
        return sql.sql("SELECT status FROM enrichment WHERE photo_id = :id").param("id", photo).query(String.class).single();
    }

    private @Nullable String error(long photo) {
        // single() takes no null
        return sql.sql("SELECT error FROM enrichment WHERE photo_id = :id").param("id", photo)
                .query((rs, row) -> rs.getString(1)).list().getFirst();
    }
}
