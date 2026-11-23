package com.teacode.kazakov.kadr.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The decoder gives the model the same pixels as Pillow gives the reference.
 */
class ReferencePixelsTest {

    /**
     * Decoded a little differently: Pillow's libwebp smooths the colors when it scales them up from
     * the half resolution WebP keeps them in, the TwelveMonkeys decoder doesn't. The vectors stay
     * close, see {@link ReferenceEmbeddingsIT}.
     */
    private static final Set<String> NOT_THE_SAME = Set.of("lossy.webp");

    private final ImageIoDecoder decoder = new ImageIoDecoder(ImageIoDecoder.DEFAULT_MAX_PIXELS);

    static Stream<Reference.ImageReference> images() {
        return Reference.read().images().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void decodesAsPillowDoes(Reference.ImageReference reference) throws Exception {
        RgbImage image = decoder.decode(reference.path(), Integer.MAX_VALUE);

        assertThat(image.width()).isEqualTo(reference.width());
        assertThat(image.height()).isEqualTo(reference.height());
        if (!NOT_THE_SAME.contains(reference.file())) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(image.pixels());
            assertThat(HexFormat.of().formatHex(digest)).isEqualTo(reference.pixelsSha256());
        }
    }
}
