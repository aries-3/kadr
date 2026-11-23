package com.teacode.kazakov.kadr.app.config;

import com.teacode.kazakov.kadr.onnx.ModelFilesMissingException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Says plainly what to do when the service doesn't start for want of the model files, instead of
 * a page of stack trace: on the first run that is the likely reason.
 */
class ModelFilesMissingFailureAnalyzer extends AbstractFailureAnalyzer<ModelFilesMissingException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ModelFilesMissingException cause) {
        Set<Path> folders = cause.missing().stream()
                .map(file -> Objects.requireNonNull(file.toAbsolutePath().getParent()))
                .collect(Collectors.toSet());
        String files = folders.size() == 1
                ? "in %s: %s".formatted(folders.iterator().next(), cause.missing().stream()
                        .map(file -> String.valueOf(file.getFileName()))
                        .collect(Collectors.joining(", ")))
                : cause.missing().stream().map(Path::toString).collect(Collectors.joining(", "));
        return new FailureAnalysis(
                "The files of the embedding model %s are missing %s.".formatted(cause.modelId(), files),
                "Download them into that folder, as the README says under \"Model\", "
                        + "or set kadr.model.dir to the folder they are in.",
                cause);
    }
}
