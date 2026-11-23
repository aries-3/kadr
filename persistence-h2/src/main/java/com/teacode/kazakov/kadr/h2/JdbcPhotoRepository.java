package com.teacode.kazakov.kadr.h2;

import com.teacode.kazakov.kadr.domain.catalog.CatalogFile;
import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.catalog.LibraryProgress;
import com.teacode.kazakov.kadr.domain.catalog.PathRange;
import com.teacode.kazakov.kadr.domain.catalog.Photo;
import com.teacode.kazakov.kadr.domain.catalog.PhotoPage;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.catalog.ScannedFile;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Photos, their files and the processing queue in the {@code photo}, {@code photo_file} and
 * {@code enrichment} tables.
 */
public final class JdbcPhotoRepository implements PhotoRepository {

    private final JdbcClient jdbc;
    /** For batches, which {@link JdbcClient} doesn't do. */
    private final JdbcTemplate batch;
    private final TransactionOperations tx;

    public JdbcPhotoRepository(JdbcTemplate jdbcTemplate, TransactionOperations tx) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        this.batch = jdbcTemplate;
        this.tx = tx;
    }

    @Override
    public List<CatalogFile> files(PathRange range) {
        return jdbc.sql("""
                        SELECT id, path, size_bytes, mtime_ms, missing FROM photo_file
                        WHERE path >= :from AND path < :to
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .query((rs, row) -> new CatalogFile(
                        rs.getLong("id"),
                        rs.getString("path"),
                        rs.getLong("size_bytes"),
                        rs.getLong("mtime_ms"),
                        rs.getBoolean("missing")))
                .list();
    }

    @Override
    public void record(List<ScannedFile> files, EnricherVersion enricher, Instant now) {
        if (files.isEmpty()) {
            return;
        }
        OffsetDateTime at = Objects.requireNonNull(SqlTime.toSql(now));
        tx.executeWithoutResult(status -> {
            Map<String, Long> photoIds = photoIds(files, at);
            batch.batchUpdate("""
                            MERGE INTO photo_file (path, photo_id, size_bytes, mtime_ms, missing)
                            KEY (path) VALUES (?, ?, ?, ?, FALSE)
                            """,
                    files, files.size(), (ps, file) -> {
                        ps.setString(1, file.path());
                        ps.setLong(2, photoIds.get(file.hash().hex()));
                        ps.setLong(3, file.sizeBytes());
                        ps.setLong(4, file.mtimeMs());
                    });
            Set<Long> photos = new LinkedHashSet<>(photoIds.values());
            batch.batchUpdate("""
                            INSERT INTO enrichment (photo_id, enricher_id, version, status, attempts, updated_at)
                            SELECT ?, ?, ?, 'PENDING', 0, ?
                            WHERE NOT EXISTS (SELECT 1 FROM enrichment WHERE photo_id = ? AND enricher_id = ?)
                            """,
                    photos, photos.size(), (ps, photoId) -> {
                        ps.setLong(1, photoId);
                        ps.setString(2, enricher.id());
                        ps.setInt(3, enricher.version());
                        ps.setObject(4, at);
                        ps.setLong(5, photoId);
                        ps.setString(6, enricher.id());
                    });
        });
    }

    /**
     * Ids of the photos with the hashes of the files; photos that don't exist yet are created.
     */
    private Map<String, Long> photoIds(List<ScannedFile> files, OffsetDateTime now) {
        Set<String> hashes = new LinkedHashSet<>();
        files.forEach(file -> hashes.add(file.hash().hex()));
        Map<String, Long> ids = findPhotoIds(hashes);
        List<String> created = hashes.stream().filter(hash -> !ids.containsKey(hash)).toList();
        if (!created.isEmpty()) {
            batch.batchUpdate("INSERT INTO photo (content_hash, created_at) VALUES (?, ?)",
                    created, created.size(), (ps, hash) -> {
                        ps.setString(1, hash);
                        ps.setObject(2, now);
                    });
            ids.putAll(findPhotoIds(created));
        }
        return ids;
    }

    private Map<String, Long> findPhotoIds(Collection<String> hashes) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.sql("SELECT id, content_hash FROM photo WHERE content_hash IN (:hashes)")
                .param("hashes", hashes)
                .query(rs -> {
                    ids.put(rs.getString("content_hash"), rs.getLong("id"));
                });
        return ids;
    }

    @Override
    public void markMissing(Collection<Long> fileIds) {
        setMissing(fileIds, true);
    }

    @Override
    public void markPresent(Collection<Long> fileIds) {
        setMissing(fileIds, false);
    }

    private void setMissing(Collection<Long> fileIds, boolean missing) {
        if (fileIds.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> batch.batchUpdate(
                "UPDATE photo_file SET missing = ? WHERE id = ?",
                fileIds, fileIds.size(), (ps, id) -> {
                    ps.setBoolean(1, missing);
                    ps.setLong(2, id);
                }));
    }

    @Override
    public LibraryProgress progress(PathRange range, String enricherId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS files,
                               COUNT(DISTINCT CASE WHEN e.status = 'DONE' THEN f.photo_id END) AS indexed,
                               COUNT(DISTINCT CASE WHEN e.status IN ('PENDING', 'RUNNING') THEN f.photo_id END) AS pending,
                               COUNT(DISTINCT CASE WHEN e.status = 'FAILED' THEN f.photo_id END) AS failed
                        FROM photo_file f
                        LEFT JOIN enrichment e ON e.photo_id = f.photo_id AND e.enricher_id = :enricher
                        WHERE f.path >= :from AND f.path < :to AND NOT f.missing
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .param("enricher", enricherId)
                .query((rs, row) -> new LibraryProgress(
                        rs.getLong("files"), rs.getLong("indexed"), rs.getLong("pending"), rs.getLong("failed")))
                .single();
    }

    @Override
    public Optional<Photo> findPhoto(long photoId) {
        return jdbc.sql("SELECT content_hash FROM photo WHERE id = :id")
                .param("id", photoId)
                .query(String.class)
                .optional()
                .map(hash -> new Photo(photoId, new ContentHash(hash), jdbc.sql("""
                                SELECT path FROM photo_file
                                WHERE photo_id = :id AND NOT missing
                                ORDER BY path
                                """)
                        .param("id", photoId)
                        .query((rs, row) -> Path.of(rs.getString("path")))
                        .list()));
    }

    @Override
    public Map<Long, List<Path>> presentFiles(Collection<Long> photoIds) {
        Map<Long, List<Path>> files = new HashMap<>();
        if (photoIds.isEmpty()) {
            return files;
        }
        jdbc.sql("""
                        SELECT photo_id, path FROM photo_file
                        WHERE photo_id IN (:ids) AND NOT missing
                        ORDER BY photo_id, path
                        """)
                .param("ids", photoIds)
                .query(rs -> {
                    files.computeIfAbsent(rs.getLong("photo_id"), id -> new ArrayList<>())
                            .add(Path.of(rs.getString("path")));
                });
        return files;
    }

    @Override
    public PhotoPage photos(PathRange range, long offset, int limit) {
        long total = jdbc.sql("""
                        SELECT COUNT(DISTINCT photo_id) FROM photo_file
                        WHERE path >= :from AND path < :to AND NOT missing
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .query(Long.class)
                .single();
        List<Long> ids = jdbc.sql("""
                        SELECT photo_id FROM photo_file
                        WHERE path >= :from AND path < :to AND NOT missing
                        GROUP BY photo_id
                        ORDER BY MIN(path), photo_id
                        OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .param("offset", offset)
                .param("limit", limit)
                .query(Long.class)
                .list();
        if (ids.isEmpty()) {
            return new PhotoPage(total, List.of());
        }
        Map<Long, String> hashes = new HashMap<>();
        Map<Long, List<Path>> files = new HashMap<>();
        jdbc.sql("""
                        SELECT f.photo_id, f.path, p.content_hash
                        FROM photo_file f
                        JOIN photo p ON p.id = f.photo_id
                        WHERE f.photo_id IN (:ids) AND f.path >= :from AND f.path < :to AND NOT f.missing
                        ORDER BY f.path
                        """)
                .param("ids", ids)
                .param("from", range.from())
                .param("to", range.to())
                .query(rs -> {
                    long id = rs.getLong("photo_id");
                    hashes.put(id, rs.getString("content_hash"));
                    files.computeIfAbsent(id, key -> new ArrayList<>()).add(Path.of(rs.getString("path")));
                });
        // A scan running meanwhile may have marked the last file of a photo missing: such a photo is left out
        List<Photo> photos = ids.stream()
                .filter(files::containsKey)
                .map(id -> new Photo(id, new ContentHash(hashes.get(id)), List.copyOf(files.get(id))))
                .toList();
        return new PhotoPage(total, photos);
    }
}
