package com.teacode.kazakov.kadr.h2;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * Instants in {@code TIMESTAMP WITH TIME ZONE} columns, stored in UTC.
 */
final class SqlTime {

    private SqlTime() {
    }

    static @Nullable OffsetDateTime toSql(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
