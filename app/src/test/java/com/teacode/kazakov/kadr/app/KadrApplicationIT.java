package com.teacode.kazakov.kadr.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

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

    @Autowired
    MockMvcTester mvc;

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
    void apiEndpointsAnswerNotImplementedUntilTheirMilestone() {
        assertThat(mvc.post().uri("/api/search/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\": \"a dog\"}"))
                .hasStatus(HttpStatus.NOT_IMPLEMENTED);
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

    @Test
    void errorsAreProblemDetails() {
        assertThat(mvc.get().uri("/api/no-such-endpoint"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
