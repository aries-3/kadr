package com.teacode.kazakov.kadr.fs;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.files.ContentHasher;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over the file size and its head and tail.
 * <p>
 * The digest covers the size as 8 bytes big-endian, then the first 64 KiB and the last 64 KiB;
 * a file of 128 KiB or less is digested whole after the size. About 128 KiB is read instead of
 * the whole file. For JPEG, PNG and WebP that is enough to tell files apart: the head holds the
 * headers and EXIF, the tail the end of the compressed data, and the size must match exactly.
 */
public final class Sha256ContentHasher implements ContentHasher {

    static final int CHUNK_SIZE = 64 * 1024;

    @Override
    public ContentHash hash(Path file) throws IOException {
        MessageDigest sha256 = sha256();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(size).flip());
            if (size <= 2L * CHUNK_SIZE) {
                digest(channel, 0, (int) size, sha256);
            } else {
                digest(channel, 0, CHUNK_SIZE, sha256);
                digest(channel, size - CHUNK_SIZE, CHUNK_SIZE, sha256);
            }
        }
        return ContentHash.of(sha256.digest());
    }

    private static void digest(FileChannel channel, long position, int length, MessageDigest digest)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new EOFException("The file got shorter while being read");
            }
        }
        digest.update(buffer.flip());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform supports SHA-256", e);
        }
    }
}
