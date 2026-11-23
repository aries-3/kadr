package com.teacode.kazakov.kadr.onnx;

import static com.teacode.kazakov.kadr.onnx.TestModel.similarity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real model: what it promises, without a reference to compare with.
 */
class SiglipModelIT {

    private SiglipModel model;

    /**
     * Loaded once for all tests; asked for by each, so each shows as skipped without the model.
     */
    @BeforeEach
    void load() {
        model = TestModel.get();
    }

    @Test
    void makesVectorsOfTheModelSize() {
        assertThat(model.id()).isEqualTo(TestModel.ID);
        assertThat(model.dimension()).isEqualTo(768);
        assertThat(model.imageSize()).isEqualTo(224);
        assertThat(model.embedTexts(List.of("a dog")).getFirst()).hasSize(768);
        assertThat(model.embedImages(List.of(plain(0xFF0000, 50, 40))).getFirst()).hasSize(768);
    }

    @Test
    void vectorsAreOfUnitLength() {
        float[] text = model.embedTexts(List.of("a dog on the beach")).getFirst();
        float[] image = model.embedImages(List.of(plain(0x3060A0, 300, 200))).getFirst();

        assertThat(similarity(text, text)).isCloseTo(1.0, within(1e-5));
        assertThat(similarity(image, image)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void theCaseOfATextDoesNotMatter() {
        List<float[]> vectors = model.embedTexts(List.of("A Dog On The Beach", "a dog on the beach"));

        assertThat(vectors.get(0)).containsExactly(vectors.get(1));
    }

    @Test
    void aBatchGivesTheVectorsOfItsItems() {
        List<String> texts = List.of("a dog", "собака на пляже", "mountains at sunset");
        List<RgbImage> images = List.of(plain(0xFF0000, 30, 20), plain(0x00FF00, 640, 480));

        List<float[]> textBatch = model.embedTexts(texts);
        List<float[]> imageBatch = model.embedImages(images);

        for (int i = 0; i < texts.size(); i++) {
            float[] alone = model.embedTexts(List.of(texts.get(i))).getFirst();
            assertThat(similarity(textBatch.get(i), alone)).as(texts.get(i)).isCloseTo(1.0, within(1e-5));
        }
        for (int i = 0; i < images.size(); i++) {
            float[] alone = model.embedImages(List.of(images.get(i))).getFirst();
            assertThat(similarity(imageBatch.get(i), alone)).as("image %d", i).isCloseTo(1.0, within(1e-5));
        }
    }

    @Test
    void aLongTextIsCutOff() {
        String text = "a dog runs along the beach at sunset ".repeat(20);

        List<float[]> vectors = model.embedTexts(List.of(text, text + "and then a cat comes"));

        // The ending is past the 64 tokens the model takes
        assertThat(vectors.get(0)).containsExactly(vectors.get(1));
    }

    @Test
    void aTextIsNearerToTheImageItDescribes() {
        List<float[]> images = model.embedImages(List.of(plain(0xE01010, 224, 224), plain(0x1030E0, 224, 224)));
        List<float[]> texts = model.embedTexts(List.of(
                "a red picture", "a blue picture", "красная картинка", "синяя картинка"));

        for (int language = 0; language < 2; language++) {
            float[] red = texts.get(language * 2);
            float[] blue = texts.get(language * 2 + 1);
            assertThat(similarity(images.get(0), red)).isGreaterThan(similarity(images.get(0), blue));
            assertThat(similarity(images.get(1), blue)).isGreaterThan(similarity(images.get(1), red));
        }
    }

    @Test
    void nothingToEmbedGivesNoVectors() {
        assertThat(model.embedTexts(List.of())).isEmpty();
        assertThat(model.embedImages(List.of())).isEmpty();
    }

    private static RgbImage plain(int rgb, int width, int height) {
        byte[] pixels = new byte[width * height * 3];
        for (int i = 0; i < pixels.length; i += 3) {
            pixels[i] = (byte) (rgb >> 16);
            pixels[i + 1] = (byte) (rgb >> 8);
            pixels[i + 2] = (byte) rgb;
        }
        return new RgbImage(width, height, pixels);
    }
}
