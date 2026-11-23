package com.teacode.kazakov.kadr.onnx;

import static com.teacode.kazakov.kadr.onnx.TestModel.similarity;
import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Java side of the model against the original one in Python: the same tokens, and vectors
 * pointing the same way. A mistake in preparing images or texts gives no error, only vectors a
 * little off, and search quietly gets worse; this is where it shows.
 */
class ReferenceEmbeddingsIT {

    /**
     * Measured: 1.000000 to six places for every text and every image but the WebP one; what is
     * left is the arithmetic of ONNX Runtime against PyTorch.
     */
    private static final double SIMILARITY = 0.9999;

    /**
     * WebP is decoded a little differently, see {@link ReferencePixelsTest}: measured 0.9939.
     */
    private static final double WEBP_SIMILARITY = 0.99;

    private SiglipModel model;

    @BeforeEach
    void load() {
        model = TestModel.get();
    }

    static Stream<Reference.ImageReference> images() {
        return Reference.read().images().stream();
    }

    static Stream<Reference.TextReference> texts() {
        return Reference.read().texts().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void imageVectorsMatchTheReference(Reference.ImageReference reference) throws Exception {
        ImageIoDecoder decoder = new ImageIoDecoder(ImageIoDecoder.DEFAULT_MAX_PIXELS);
        RgbImage image = decoder.decode(reference.path(), TestModel.DECODING_FACTOR * model.imageSize());

        double similarity = similarity(model.embedImages(List.of(image)).getFirst(), reference.embedding());

        System.out.printf("%-22s %.6f%n", reference.file(), similarity);
        assertThat(similarity).isGreaterThanOrEqualTo(reference.file().endsWith(".webp") ? WEBP_SIMILARITY : SIMILARITY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("texts")
    void textsGiveTheReferenceTokensAndVectors(Reference.TextReference reference) throws Exception {
        try (TextTokenizer tokenizer = TextTokenizer.open(TestModel.FOLDER.resolve("tokenizer.json"))) {
            assertThat(tokenizer.ids(List.of(reference.text()))).containsExactly(reference.inputIds());
        }

        double similarity = similarity(model.embedTexts(List.of(reference.text())).getFirst(), reference.embedding());

        System.out.printf("%-40.40s %.6f%n", reference.text(), similarity);
        assertThat(similarity).isGreaterThanOrEqualTo(SIMILARITY);
    }
}
