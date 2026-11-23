package com.teacode.kazakov.kadr.onnx;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Files of a model aren't where they should be: they aren't in the repository and are downloaded
 * by hand.
 */
public final class ModelFilesMissingException extends IllegalStateException {

    private final String modelId;
    private final List<Path> missing;

    /**
     * @param modelId the model
     * @param missing the files that aren't there
     */
    public ModelFilesMissingException(String modelId, List<Path> missing) {
        super("The files of the model %s are missing: %s".formatted(modelId,
                missing.stream().map(Path::toString).collect(Collectors.joining(", "))));
        this.modelId = modelId;
        this.missing = List.copyOf(missing);
    }

    /**
     * {@return the model}
     */
    public String modelId() {
        return modelId;
    }

    /**
     * {@return the files that aren't there}
     */
    public List<Path> missing() {
        return missing;
    }
}
