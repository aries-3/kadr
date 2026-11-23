package com.teacode.kazakov.kadr.onnx;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the original model computes in Python for the test images and texts:
 * {@code reference/reference.json}, made by {@code tools/reference/reference.py}.
 *
 * @param model    the Hugging Face model
 * @param revision its commit
 * @param images   the test images
 * @param texts    the test texts
 */
record Reference(String model, String revision, List<ImageReference> images, List<TextReference> texts) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    static Reference read() {
        try (InputStream json = Reference.class.getResourceAsStream("/reference/reference.json")) {
            return JSON.readValue(Objects.requireNonNull(json, "reference/reference.json"), Reference.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param file          its name in {@code reference/images}
     * @param width         upright, in pixels
     * @param height        upright, in pixels
     * @param pixelsSha256  the digest of its RGB pixels as Pillow decodes it, row by row
     * @param embedding     its unit vector
     */
    record ImageReference(String file, int width, int height, String pixelsSha256, float[] embedding) {

        Path path() {
            try {
                return Path.of(Objects.requireNonNull(Reference.class.getResource("/reference/images/" + file)).toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String toString() {
            return file;
        }
    }

    /**
     * @param text      as given to the model, before lowercasing
     * @param inputIds  the token ids
     * @param embedding its unit vector
     */
    record TextReference(String text, long[] inputIds, float[] embedding) {

        @Override
        public String toString() {
            return text;
        }
    }
}
