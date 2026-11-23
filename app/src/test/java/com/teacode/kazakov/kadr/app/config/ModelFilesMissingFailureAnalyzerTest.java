package com.teacode.kazakov.kadr.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.onnx.ModelFilesMissingException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

class ModelFilesMissingFailureAnalyzerTest {

    private final ModelFilesMissingFailureAnalyzer analyzer = new ModelFilesMissingFailureAnalyzer();

    @Test
    void namesTheFolderAndTheFilesMissingInIt() {
        Path folder = Path.of("/data/models/siglip");
        var missing = new ModelFilesMissingException("siglip",
                List.of(folder.resolve("vision_model.onnx"), folder.resolve("tokenizer.json")));

        FailureAnalysis analysis = analyzer.analyze(new BeanCreationException("embeddingModel", "failed", missing));

        assertThat(analysis.getDescription()).isEqualTo(
                "The files of the embedding model siglip are missing in /data/models/siglip: vision_model.onnx, tokenizer.json.");
        assertThat(analysis.getAction()).contains("README", "kadr.model.dir");
    }

    @Test
    void namesFilesInSeveralFoldersByTheirPaths() {
        var missing = new ModelFilesMissingException("siglip",
                List.of(Path.of("/a/vision_model.onnx"), Path.of("/b/tokenizer.json")));

        FailureAnalysis analysis = analyzer.analyze(missing);

        assertThat(analysis.getDescription()).endsWith("missing /a/vision_model.onnx, /b/tokenizer.json.");
    }
}
