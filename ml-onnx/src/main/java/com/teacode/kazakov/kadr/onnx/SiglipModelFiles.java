package com.teacode.kazakov.kadr.onnx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The files of a SigLIP 2 model exported to ONNX, as in the {@code onnx-community} repositories on
 * Hugging Face.
 *
 * @param visionModel        the image tower, e.g. {@code vision_model.onnx}
 * @param textModel          the text tower, e.g. {@code text_model.onnx}
 * @param tokenizer          {@code tokenizer.json}
 * @param preprocessorConfig {@code preprocessor_config.json}: how images are prepared
 */
public record SiglipModelFiles(Path visionModel, Path textModel, Path tokenizer, Path preprocessorConfig) {

    /**
     * The files under their usual names in a folder: the full-precision towers.
     *
     * @param folder where the files are
     * @return the files
     */
    public static SiglipModelFiles in(Path folder) {
        return new SiglipModelFiles(folder.resolve("vision_model.onnx"), folder.resolve("text_model.onnx"),
                folder.resolve("tokenizer.json"), folder.resolve("preprocessor_config.json"));
    }

    /**
     * {@return the files that aren't there}
     */
    public List<Path> missing() {
        return List.of(visionModel, textModel, tokenizer, preprocessorConfig).stream()
                .filter(file -> !Files.isRegularFile(file))
                .toList();
    }
}
