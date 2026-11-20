package com.teacode.kazakov.kadr.fs;

import static com.teacode.kazakov.kadr.fs.Sha256ContentHasher.CHUNK_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256ContentHasherTest {

    private final Sha256ContentHasher hasher = new Sha256ContentHasher();
    private final Random random = new Random(42);

    @TempDir
    Path folder;

    @Test
    void smallFileIsHashedWhole() throws IOException {
        byte[] content = bytes(1000);

        assertThat(hash(content)).isEqualTo(expected(content.length, content));
    }

    @Test
    void fileOfTwoChunksIsHashedWhole() throws IOException {
        byte[] content = bytes(2 * CHUNK_SIZE);

        assertThat(hash(content)).isEqualTo(expected(content.length, content));
    }

    @Test
    void largeFileIsHashedByHeadAndTail() throws IOException {
        byte[] content = bytes(2 * CHUNK_SIZE + 1);
        byte[] head = Arrays.copyOfRange(content, 0, CHUNK_SIZE);
        byte[] tail = Arrays.copyOfRange(content, content.length - CHUNK_SIZE, content.length);

        assertThat(hash(content)).isEqualTo(expected(content.length, head, tail));
    }

    @Test
    void emptyFileHasAHash() throws IOException {
        assertThat(hash(new byte[0])).isEqualTo(expected(0));
    }

    @Test
    void middleOfALargeFileDoesNotCount() throws IOException {
        byte[] content = bytes(5 * CHUNK_SIZE);
        ContentHash before = hash(content);

        content[content.length / 2] ^= 1;

        assertThat(hash(content)).isEqualTo(before);
    }

    @Test
    void headTailAndSizeCount() throws IOException {
        byte[] content = bytes(5 * CHUNK_SIZE);
        ContentHash original = hash(content);

        byte[] head = content.clone();
        head[0] ^= 1;
        byte[] tail = content.clone();
        tail[tail.length - 1] ^= 1;
        byte[] longer = Arrays.copyOf(content, content.length + 1);

        assertThat(hash(head)).isNotEqualTo(original);
        assertThat(hash(tail)).isNotEqualTo(original);
        assertThat(hash(longer)).isNotEqualTo(original);
    }

    private ContentHash hash(byte[] content) throws IOException {
        Path file = Files.createTempFile(folder, "photo", ".jpg");
        Files.write(file, content);
        return hasher.hash(file);
    }

    private byte[] bytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static ContentHash expected(long size, byte[]... parts) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
            for (byte[] part : parts) {
                sha256.update(part);
            }
            return ContentHash.of(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
