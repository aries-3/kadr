package com.teacode.kazakov.kadr.app;

import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.util.List;

/**
 * Stands in for the real model in the tests of the service: its files aren't in the repository,
 * and loading it takes seconds and gigabytes. A test puts it in place of the real one with
 * <pre>
 * &#64;TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
 * EmbeddingModel model;
 * </pre>
 * Its vector of an image follows the mean color, so the same image gets the same vector and
 * images of other colors get other ones.
 */
public final class FakeEmbeddingModel implements EmbeddingModel {

    public static final String ID = "fake";

    public static EmbeddingModel create() {
        return new FakeEmbeddingModel();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int dimension() {
        return 4;
    }

    @Override
    public int imageSize() {
        return 224;
    }

    @Override
    public List<float[]> embedImages(List<RgbImage> images) {
        return images.stream().map(FakeEmbeddingModel::meanColor).toList();
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        return texts.stream()
                .map(text -> unit(new float[] {text.hashCode() & 0xFF, text.hashCode() >> 8 & 0xFF, text.length(), 1}))
                .toList();
    }

    private static float[] meanColor(RgbImage image) {
        double[] sums = new double[3];
        byte[] pixels = image.pixels();
        for (int i = 0; i < pixels.length; i++) {
            sums[i % 3] += pixels[i] & 0xFF;
        }
        int count = image.width() * image.height();
        return unit(new float[] {(float) (sums[0] / count), (float) (sums[1] / count), (float) (sums[2] / count), 1});
    }

    private static float[] unit(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        float length = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }
}
