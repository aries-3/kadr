package com.teacode.kazakov.kadr.domain.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RgbImageTest {

    @Test
    void holdsThreeBytesAPixel() {
        RgbImage image = new RgbImage(2, 3, new byte[18]);

        assertThat(image.width()).isEqualTo(2);
        assertThat(image.height()).isEqualTo(3);
    }

    @Test
    void pixelsMustMatchTheSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RgbImage(2, 3, new byte[17]))
                .withMessage("17 bytes for 2 × 3 RGB pixels");
    }

    @Test
    void anImageHasPixels() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RgbImage(0, 3, new byte[0]));
    }
}
