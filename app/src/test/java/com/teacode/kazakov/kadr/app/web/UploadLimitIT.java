package com.teacode.kazakov.kadr.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The upload limit, on a real server: the test client of Spring MVC doesn't parse uploads, so it
 * doesn't apply the limit. Acceptance criterion 8, a file too large.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadLimitIT {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("spring.servlet.multipart.max-file-size", () -> "1KB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "2KB");
    }

    /** The real model isn't loaded: see {@link com.teacode.kazakov.kadr.app.FakeEmbeddingModel}. */
    @TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
    EmbeddingModel model;

    @Value("${local.server.port}")
    int port;

    @Test
    void aFileOverTheLimitIsRefused() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(new byte[1500]) {
            @Override
            public String getFilename() {
                return "large.jpg";
            }
        });

        ResponseEntity<String> response = RestClient.create("http://127.0.0.1:" + port).post()
                .uri("/api/search/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .onStatus(status -> true, (request, answer) -> {
                })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
