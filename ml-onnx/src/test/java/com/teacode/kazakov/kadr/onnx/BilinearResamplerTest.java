package com.teacode.kazakov.kadr.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BilinearResamplerTest {

    /**
     * The expected pixels are those of Pillow 10.2: {@code Image.resize((width, height),
     * Image.Resampling.BILINEAR)} of the same pattern, as hex or, for large results, their SHA-256.
     */
    @ParameterizedTest(name = "{0} × {1} to {2} × {3}")
    @CsvSource({
            "9, 7, 4, 3, 6a879285879083867d8a6f5e7590777b76757e677699937576998d779487576f7a91737b",
            "9, 7, 13, 11, 00356a14497e2e6398477cb16196cb7aafe494c9feaee366c79a31cd164b362f6414497e285d92295e934075aa5d92ac7aaf7998858d9075aa5d92c77aaf83979675aa5e8a667a56636258793a6f6398b67ea6d19abdbdacdf3dcd724ea63b68275c7a496e9c6288bd77accda0d545c4832ede13489dd24dbc8c6bb75d825d8378757f9a878b877db22da4585483467b3e73949dd242d79641f52a5f7aaf419c7963a25a795d85647c78898b7f8276ab3dbe4245942c61275c8585ba71c1ab5de3754d11467b376ca1679c9698cd15c86032aa2e63295e93a82b609830652b60955c91c68cc180b2e71c4b80b575aa8784b95b487d50716d7e6e639824598e766a9f948f8689be5c7fb4c39f8993c95b3385baa98dc2727cb156346991497eb35489a8467b6a4f84a4809a88c0b43e82a6b1a26196e0154abff44073a86540759970a5d85d92c7659a899cd11d396e5c593460c322415958888f4d82f72c616da2636398977186bbb5699ea1a07f83b869669b5c2358693f477c935d92516ba0807f73d0913a22578c598ec3a19bd0e83166d7aa4199ce522f649913487d2f64996ba0d54f84b976ab69ade217",
            "9, 7, 9, 3, 4e83817c7bb074a970a26a9f63989692906989639874a93aa333685c917b938c856196529e5b904a7f69b03f74577d7587bca5d3723d79ae75689d9c8f699e7cb18b60955c1d5287ab7270497eb3d76741",
            "9, 7, 4, 7, 1f54896a9fcdac9c673d3e7383b8baa670615887a9b38950b47b51718d8a96614bb88d554b80829d62527e427778ad837cb17843789a77ac9caf858772a77d699e9b6454519a3d727779aea89863356a9874a97f",
            "37, 23, 224, 224, sha256:d0decd1aac16a69da9804bae096d2d521df0330c50a7329b076aa77fad6715cf",
            "640, 427, 224, 224, sha256:579cdd3d4dac37fd36b1945602b562984474418095576f8a01e56283d5e5ffc5",
            "1000, 333, 224, 224, sha256:410725ea6d9ff6da62f3371467d5b2fc1822dad55e1790178d1e4b314cda9e37",
            "5, 600, 224, 224, sha256:64bae7db28533ad3419cbb5b3b6b19936eb37d58a2210888887431ebe5105acf",
    })
    void resizesAsPillowDoes(int sourceWidth, int sourceHeight, int width, int height, String expected)
            throws Exception {
        RgbImage resized = BilinearResampler.resize(pattern(sourceWidth, sourceHeight), width, height);

        assertThat(resized.width()).isEqualTo(width);
        assertThat(resized.height()).isEqualTo(height);
        if (expected.startsWith("sha256:")) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resized.pixels());
            assertThat("sha256:" + HexFormat.of().formatHex(digest)).isEqualTo(expected);
        } else {
            assertThat(HexFormat.of().formatHex(resized.pixels())).isEqualTo(expected);
        }
    }

    @Test
    void anImageOfThatSizeStaysAsItIs() {
        RgbImage image = pattern(5, 4);

        assertThat(BilinearResampler.resize(image, 5, 4)).isSameAs(image);
    }

    @Test
    void aPlainColorStaysPlain() {
        byte[] pixels = new byte[300 * 200 * 3];
        for (int i = 0; i < pixels.length; i += 3) {
            pixels[i] = (byte) 200;
            pixels[i + 1] = 100;
            pixels[i + 2] = 7;
        }

        RgbImage resized = BilinearResampler.resize(new RgbImage(300, 200, pixels), 224, 224);

        for (int i = 0; i < resized.pixels().length; i += 3) {
            assertThat(resized.pixels()[i] & 0xFF).isEqualTo(200);
            assertThat(resized.pixels()[i + 1]).isEqualTo((byte) 100);
            assertThat(resized.pixels()[i + 2]).isEqualTo((byte) 7);
        }
    }

    /**
     * The pattern the expected results were made from.
     */
    private static RgbImage pattern(int width, int height) {
        byte[] pixels = new byte[width * height * 3];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < 3; c++) {
                    pixels[i++] = (byte) (x * 37 + y * 91 + c * 53 + (x * y) % 13 * 11);
                }
            }
        }
        return new RgbImage(width, height, pixels);
    }
}
