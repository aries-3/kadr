package com.teacode.kazakov.kadr.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.teacode.kazakov.kadr.app.RealModel;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Search from end to end with the real model, both towers: photos indexed, then found through the
 * API by a text and by an image. Acceptance criterion 7, its first part. Needs the model files,
 * which aren't in the repository, and memory for the model: skipped otherwise, see {@link RealModel}.
 */
@SpringBootTest
// Closed after the class: the model takes gigabytes
@DirtiesContext
@AutoConfigureMockMvc
class RealModelSearchIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);
    private static final List<String> COLORS = List.of("red", "green", "blue");

    @TempDir
    static Path dataDir;

    @TempDir
    static Path photos;

    @BeforeAll
    static void modelIsThere() throws IOException {
        RealModel.assumeAvailable(3072);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        registry.add("kadr.model.dir", RealModel.FOLDER::toString);
        // The tests index the photos themselves
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("kadr.indexing.idle-pause", () -> "50ms");
    }

    @MockitoBean
    ScanLauncher launcher;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LibraryService libraries;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    Indexer indexer;

    @Autowired
    VectorIndex index;

    @Test
    void aTextFindsThePhotoItDescribes() throws IOException {
        indexColors();

        for (String[] query : new String[][] {
                {"a red picture", "red"}, {"a blue picture", "blue"}, {"красная картинка", "red"}, {"зелёная картинка", "green"}}) {
            MvcTestResult result = mvc.post().uri("/api/search/text").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\": \"%s\", \"limit\": 3}".formatted(query[0])).exchange();

            assertThat(result).hasStatusOk();
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.items[0].files[0].path"))
                    .as(query[0]).endsWith(query[1] + ".jpg");
        }
    }

    @Test
    void aPhotoOfTheCatalogFindsItselfFirst() throws IOException {
        indexColors();
        byte[] blue = Files.readAllBytes(photos.toRealPath().resolve("blue.jpg"));

        MvcTestResult result = mvc.post().uri("/api/search/image").multipart()
                .file(new MockMultipartFile("image", "blue.jpg", "image/jpeg", blue)).exchange();

        assertThat(result).hasStatusOk();
        String json = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.items[0].files[0].path")).endsWith("blue.jpg");
        assertThat(JsonPath.<Double>read(json, "$.items[0].score")).isGreaterThan(0.99);
    }

    /**
     * Plain red, green and blue photos, scanned and indexed once for the tests of the class.
     */
    private void indexColors() throws IOException {
        if (index.size() == COLORS.size()) {
            return;
        }
        Path folder = photos.toRealPath();
        for (String name : COLORS) {
            Color color = colorOf(name);
            BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(color);
            graphics.fillRect(0, 0, 400, 300);
            graphics.dispose();
            assertThat(ImageIO.write(image, "jpeg", folder.resolve(name + ".jpg").toFile())).isTrue();
        }
        long id = libraries.add(folder.toString()).library().id();
        scanner.scan(id, () -> false);
        Instant deadline = Instant.now().plus(TIMEOUT);
        indexer.run(() -> libraries.get(id).progress().pending() == 0 || Instant.now().isAfter(deadline));
        await().atMost(TIMEOUT).until(() -> index.size() == COLORS.size());
    }

    private static Color colorOf(String name) {
        return switch (name) {
            case "red" -> new Color(0xE01010);
            case "green" -> new Color(0x10B020);
            default -> new Color(0x1030E0);
        };
    }
}
