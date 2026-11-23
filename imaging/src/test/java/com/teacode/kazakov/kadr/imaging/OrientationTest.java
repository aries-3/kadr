package com.teacode.kazakov.kadr.imaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.awt.geom.Point2D;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrientationTest {

    private static final int WIDTH = 3;
    private static final int HEIGHT = 2;

    /**
     * Moving the pixels must agree with the transform the thumbnails are drawn with: each stored
     * pixel lands where the transform takes its center.
     */
    @ParameterizedTest
    @EnumSource(Orientation.class)
    void movesThePixelsAsTheTransformDoes(Orientation orientation) {
        RgbImage stored = numbered();

        RgbImage upright = orientation.upright(stored);

        assertThat(upright.width()).isEqualTo(orientation.swapsSides() ? HEIGHT : WIDTH);
        assertThat(upright.height()).isEqualTo(orientation.swapsSides() ? WIDTH : HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Point2D center = orientation.upright(WIDTH, HEIGHT)
                        .transform(new Point2D.Double(x + 0.5, y + 0.5), null);
                int target = (int) center.getY() * upright.width() + (int) center.getX();
                int source = y * WIDTH + x;
                for (int channel = 0; channel < 3; channel++) {
                    assertThat(upright.pixels()[target * 3 + channel])
                            .as("pixel (%d, %d), channel %d", x, y, channel)
                            .isEqualTo(stored.pixels()[source * 3 + channel]);
                }
            }
        }
    }

    /**
     * Every pixel and channel of a different value.
     */
    private static RgbImage numbered() {
        byte[] pixels = new byte[WIDTH * HEIGHT * 3];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 7);
        }
        return new RgbImage(WIDTH, HEIGHT, pixels);
    }
}
