package com.teacode.kazakov.kadr.h2;

import com.teacode.kazakov.kadr.domain.catalog.Library;
import com.teacode.kazakov.kadr.domain.catalog.LibraryRepository;
import com.teacode.kazakov.kadr.domain.catalog.ScanOutcome;
import com.teacode.kazakov.kadr.domain.catalog.ScanState;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Folders in the {@code library_root} table.
 */
public final class JdbcLibraryRepository implements LibraryRepository {

    /** Length of the {@code scan_error} column. */
    static final int MAX_ERROR_LENGTH = 4000;

    private static final String SELECT = """
            SELECT id, path, scan_state, scan_outcome, scan_error,
                   last_scan_started_at, last_scan_finished_at, created_at
            FROM library_root
            """;

    private final JdbcClient jdbc;

    public JdbcLibraryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Library> add(Path path, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO library_root (path, scan_state, created_at)
                            VALUES (:path, :state, :now)
                            """)
                    .param("path", path.toString())
                    .param("state", ScanState.SCANNING.name())
                    .param("now", SqlTime.toSql(now))
                    .update(key, "id");
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        return findById(Objects.requireNonNull(key.getKeyAs(Long.class)));
    }

    @Override
    public Optional<Library> findById(long id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(JdbcLibraryRepository::map).optional();
    }

    @Override
    public List<Library> findAll() {
        return jdbc.sql(SELECT + "ORDER BY id").query(JdbcLibraryRepository::map).list();
    }

    @Override
    public List<Library> findByScanState(ScanState state) {
        return jdbc.sql(SELECT + "WHERE scan_state = :state ORDER BY id")
                .param("state", state.name())
                .query(JdbcLibraryRepository::map)
                .list();
    }

    @Override
    public boolean requestScan(long id) {
        return jdbc.sql("""
                        UPDATE library_root SET scan_state = :scanning
                        WHERE id = :id AND scan_state = :idle
                        """)
                .param("id", id)
                .param("scanning", ScanState.SCANNING.name())
                .param("idle", ScanState.IDLE.name())
                .update() == 1;
    }

    @Override
    public void scanStarted(long id, Instant at) {
        jdbc.sql("UPDATE library_root SET last_scan_started_at = :at WHERE id = :id")
                .param("id", id)
                .param("at", SqlTime.toSql(at))
                .update();
    }

    @Override
    public void scanFinished(long id, ScanOutcome outcome, @Nullable String error, Instant at) {
        jdbc.sql("""
                        UPDATE library_root
                        SET scan_state = :idle, scan_outcome = :outcome, scan_error = :error,
                            last_scan_finished_at = :at
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("idle", ScanState.IDLE.name())
                .param("outcome", outcome.name())
                .param("error", truncate(error))
                .param("at", SqlTime.toSql(at))
                .update();
    }

    private static @Nullable String truncate(@Nullable String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static Library map(ResultSet rs, int row) throws SQLException {
        String outcome = rs.getString("scan_outcome");
        return new Library(
                rs.getLong("id"),
                Path.of(rs.getString("path")),
                ScanState.valueOf(rs.getString("scan_state")),
                outcome == null ? null : ScanOutcome.valueOf(outcome),
                rs.getString("scan_error"),
                SqlTime.instant(rs, "last_scan_started_at"),
                SqlTime.instant(rs, "last_scan_finished_at"),
                Objects.requireNonNull(SqlTime.instant(rs, "created_at")));
    }
}
