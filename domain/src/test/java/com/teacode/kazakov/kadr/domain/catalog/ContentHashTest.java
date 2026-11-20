package com.teacode.kazakov.kadr.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ContentHashTest {

    @Test
    void formatsADigestAsLowercaseHex() {
        byte[] digest = new byte[32];
        digest[0] = (byte) 0xAB;

        assertThat(ContentHash.of(digest).hex()).isEqualTo("ab" + "0".repeat(62));
    }

    @Test
    void acceptsOnlySha256Hex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentHash("ab"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentHash("AB" + "0".repeat(62)));
    }
}
