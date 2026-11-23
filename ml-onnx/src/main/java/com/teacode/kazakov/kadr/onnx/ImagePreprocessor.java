package com.teacode.kazakov.kadr.onnx;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an image into the input of the vision model, as the Hugging Face
 * {@code SiglipImageProcessor} does with the settings of the model's
 * {@code preprocessor_config.json}:
 * <ol>
 *   <li>scales it to the model's square, without keeping the aspect ratio, as the model was
 *       trained; bilinear, as Pillow does it;</li>
 *   <li>scales the values from 0–255 to 0–1, then subtracts the mean and divides by the standard
 *       deviation of each channel: with SigLIP's 0.5 and 0.5 the values are from −1 to 1;</li>
 *   <li>writes the channels one after another: the model takes red, green and blue planes.</li>
 * </ol>
 * The arithmetic is that of Hugging Face too: the value times the factor in double, the rest in
 * float.
 */
final class ImagePreprocessor {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Pillow's number of the bilinear filter. */
    private static final int BILINEAR = 2;

    private final int size;
    /** The model input of each 8-bit value, by channel. */
    private final float[][] values = new float[3][256];

    private ImagePreprocessor(int size, double rescaleFactor, float[] mean, float[] std) {
        this.size = size;
        for (int channel = 0; channel < 3; channel++) {
            for (int value = 0; value < 256; value++) {
                float rescaled = (float) (value * rescaleFactor);
                values[channel][value] = (rescaled - mean[channel]) / std[channel];
            }
        }
    }

    /**
     * Reads the settings of the model.
     *
     * @param file {@code preprocessor_config.json}
     * @return the preprocessor
     * @throws IllegalStateException if the file can't be read or asks for processing this class
     *                               doesn't do
     */
    static ImagePreprocessor read(Path file) {
        Config config;
        try {
            config = JSON.readValue(file.toFile(), Config.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Can't read %s: %s".formatted(file, e.getOriginalMessage()), e);
        }
        List<String> unsupported = config.unsupported();
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException("%s asks for image processing that isn't supported: %s"
                    .formatted(file, String.join("; ", unsupported)));
        }
        return new ImagePreprocessor(Objects.requireNonNull(config.size()).width(), config.rescaleFactor(),
                Objects.requireNonNull(config.imageMean()), Objects.requireNonNull(config.imageStd()));
    }

    /**
     * {@return the side of the square the images are scaled to, in pixels}
     */
    int size() {
        return size;
    }

    /**
     * Writes the model input for an image at the current position of a buffer and moves the
     * position past it.
     *
     * @param image  an image of any size
     * @param target room for 3 × size × size values
     */
    void write(RgbImage image, FloatBuffer target) {
        byte[] pixels = BilinearResampler.resize(image, size, size).pixels();
        int plane = size * size;
        int start = target.position();
        for (int channel = 0; channel < 3; channel++) {
            float[] channelValues = values[channel];
            int offset = start + channel * plane;
            for (int i = 0; i < plane; i++) {
                target.put(offset + i, channelValues[pixels[i * 3 + channel] & 0xFF]);
            }
        }
        target.position(start + 3 * plane);
    }

    /**
     * The settings in {@code preprocessor_config.json}, with the names in snake case there. A missing
     * flag is false.
     *
     * @param imageProcessorType the Hugging Face class meant to process the images
     * @param doResize           whether the images are resized
     * @param size               the size they are resized to
     * @param resample           the Pillow filter of the resizing
     * @param doRescale          whether the values are rescaled
     * @param rescaleFactor      the factor the 0–255 values are multiplied by
     * @param doNormalize        whether the values are normalized
     * @param imageMean          what is subtracted from the values, by channel
     * @param imageStd           what the values are divided by then, by channel
     */
    private record Config(
            @Nullable String imageProcessorType,
            boolean doResize,
            @Nullable Size size,
            int resample,
            boolean doRescale,
            double rescaleFactor,
            boolean doNormalize,
            float @Nullable [] imageMean,
            float @Nullable [] imageStd) {

        /**
         * {@return what differs from the processing this class does}
         */
        List<String> unsupported() {
            List<String> problems = new ArrayList<>();
            if (!"SiglipImageProcessor".equals(imageProcessorType)) {
                problems.add("image_processor_type is " + imageProcessorType);
            }
            if (!doResize || size == null || size.width() != size.height() || size.width() < 1) {
                problems.add("not resized to a square");
            }
            if (resample != BILINEAR) {
                problems.add("resample is " + resample + ", not bilinear (2)");
            }
            if (!doRescale || !doNormalize) {
                problems.add("not rescaled and normalized");
            }
            if (imageMean == null || imageMean.length != 3 || imageStd == null || imageStd.length != 3) {
                problems.add("image_mean and image_std must have a value for each of red, green and blue");
            }
            return problems;
        }
    }

    /**
     * @param width  in pixels
     * @param height in pixels
     */
    private record Size(int width, int height) {
    }
}
