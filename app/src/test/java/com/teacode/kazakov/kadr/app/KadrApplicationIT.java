package com.teacode.kazakov.kadr.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
class KadrApplicationIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        // A directory that doesn't exist yet: the service must create it
        registry.add("kadr.data-dir", () -> tempDir.resolve("data").toString());
    }

    /** The real model isn't loaded: see {@link FakeEmbeddingModel}. */
    @TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
    EmbeddingModel model;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    VectorIndex vectorIndex;

    @Test
    void createsDataDirectoryWithCatalog() {
        assertThat(tempDir.resolve("data/catalog.mv.db")).isRegularFile();
    }

    @Test
    void healthIsUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void servesTheWebInterface() {
        assertThat(mvc.get().uri("/")).hasForwardedUrl("index.html");
        // Pages built from the templates, each with the shared header and its own script
        Map<String, String> scripts = Map.of(
                "index.html", "js/libraries.js", "library.html", "js/library.js", "search.html", "js/search.js");
        scripts.forEach((page, script) -> assertThat(mvc.get().uri("/" + page)).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                .bodyText().contains("<header class=\"top\">", script));
        assertThat(mvc.get().uri("/js/libraries.js")).hasStatusOk();
        assertThat(mvc.get().uri("/kadr.css")).hasStatusOk();
    }

    /** The indexing runs in the background: a folder added gets ready by itself. */
    @Test
    void indexesFoldersInTheBackground(@TempDir Path photos) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x123456);
        ImageIO.write(image, "png", photos.resolve("a.png").toFile());
        MvcTestResult added = mvc.post().uri("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"%s\"}".formatted(photos.toRealPath())).exchange();
        long id = JsonPath.<Number>read(added.getResponse().getContentAsString(), "$.id").longValue();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/libraries/{id}", id)).bodyJson()
                        .isLenientlyEqualTo("{\"status\": \"READY\", \"files\": 1, \"indexed\": 1}"));
        // And the vector index takes the vector from the catalog
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(vectorIndex.size()).isEqualTo(1));
    }

    @Test
    void errorsAreProblemDetails() {
        assertThat(mvc.get().uri("/api/no-such-endpoint"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
