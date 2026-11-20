package com.teacode.kazakov.kadr.domain.catalog;

import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Identity hash of a file's content: equal for copies of a file, so copies are one photo.
 *
 * @param hex SHA-256 digest, 64 lowercase hex digits
 */
public record ContentHash(String hex) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * Checks the digest.
     *
     * @param hex SHA-256 digest, 64 lowercase hex digits
     * @throws IllegalArgumentException if {@code hex} isn't 64 lowercase hex digits
     */
    public ContentHash {
        if (!SHA256_HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest: " + hex);
        }
    }

    /**
     * Converts a computed digest to hex.
     *
     * @param sha256 SHA-256 digest, 32 bytes
     * @return the hash of the digest
     * @throws IllegalArgumentException if the digest isn't 32 bytes long
     */
    public static ContentHash of(byte[] sha256) {
        return new ContentHash(HexFormat.of().formatHex(sha256));
    }

    @Override
    public String toString() {
        return hex;
    }
}
