package com.teacode.kazakov.kadr.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The search API with the real database, decoder and index, and the fake model: what is answered
 * and the errors, acceptance criterion 8. The fake model makes the vector of an image from its mean
 * color, so an image finds itself and images of the same color.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        // The tests index their photos themselves
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("kadr.indexing.idle-pause", () -> "50ms");
        // So that an upload can be too large in pixels
        registry.add("kadr.indexing.max-megapixels", () -> "1");
    }

    /** The real model isn't loaded: see {@link com.teacode.kazakov.kadr.app.FakeEmbeddingModel}. */
    @TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
    EmbeddingModel model;

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

    @Autowired
    EmbeddingRepository embeddings;

    @TempDir
    Path temp;

    private Path photos;
    private final Random random = new Random();

    @BeforeEach
    void createFolder() throws IOException {
        photos = Files.createDirectory(temp.resolve("photos")).toRealPath();
    }

    @Test
    void aTextFindsPhotosWithTheirFilesTheMostSimilarFirst() throws IOException {
        Path a = image("a.png", randomColor(), 40, 30);
        Files.copy(a, photos.resolve("copy-of-a.png"));
        image("b.png", randomColor(), 40, 30);
        image("c.png", randomColor(), 40, 30);
        index();

        MvcTestResult result = text("{\"query\": \"собака на пляже\", \"limit\": 2}");

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(json, "$.modelId")).isEqualTo("fake");
        List<Double> scores = JsonPath.read(json, "$.items[*].score");
        assertThat(scores).hasSize(2).isSortedAccordingTo((x, y) -> Double.compare(y, x));
        List<List<Map<String, String>>> files = JsonPath.read(json, "$.items[*].files");
        assertThat(files).allSatisfy(photoFiles -> assertThat(photoFiles).isNotEmpty());
    }

    @Test
    void theLimitIsTwentyWhenNotGiven() throws IOException {
        for (int i = 0; i < 25; i++) {
            image(i + ".png", randomColor(), 8, 8);
        }
        index();

        MvcTestResult result = text("{\"query\": \"dog\"}");

        assertThat(result).hasStatusOk();
        assertThat(JsonPath.<List<?>>read(result.getResponse().getContentAsString(), "$.items")).hasSize(20);
    }

    /** Criterion 7, its first part, with the fake model: a photo of the catalog finds itself first. */
    @Test
    void anImageFindsItselfFirst() throws IOException {
        Path wanted = image("wanted.jpg", randomColor(), 60, 40);
        image("other.png", randomColor(), 60, 40);
        index();

        MvcTestResult result = image(Files.readAllBytes(wanted), "5");

        assertThat(result).hasStatusOk();
        String json = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.items[0].files[0].path")).isEqualTo(wanted.toString());
        assertThat(JsonPath.<Double>read(json, "$.items[0].score")).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void aPhotoWhoseFilesAreGoneIsLeftOut() throws IOException {
        Color color = randomColor();
        Path gone = image("gone.png", color, 30, 30);
        image("kept.png", randomColor(), 30, 30);
        long id = index();
        byte[] content = Files.readAllBytes(gone);
        Files.delete(gone);
        libraries.rescan(id);
        scanner.scan(id, () -> false);

        String json = image(content, null).getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(json, "$.items[*].files[*].path")).doesNotContain(gone.toString());
    }

    /** Criterion 8: the query is checked. */
    @Test
    void aBlankOrTooLongQueryOrALimitOutOfRangeIsRefused() {
        assertProblem(text("{\"query\": \"  \"}"), HttpStatus.BAD_REQUEST, "The query is blank");
        assertProblem(text("{\"query\": \"\"}"), HttpStatus.BAD_REQUEST, null);
        assertProblem(text("{}"), HttpStatus.BAD_REQUEST, null);
        assertProblem(text("{\"query\": \"%s\"}".formatted("x".repeat(1001))), HttpStatus.BAD_REQUEST, null);
        assertProblem(text("{\"query\": \"dog\", \"limit\": 0}"), HttpStatus.BAD_REQUEST, null);
        assertProblem(text("{\"query\": \"dog\", \"limit\": 201}"), HttpStatus.BAD_REQUEST, null);
    }

    /** Criterion 8: the image is checked. */
    @Test
    void anImageThatIsNotOneOrIsDamagedOrTooLargeIsRefused() throws IOException {
        byte[] jpeg = Files.readAllBytes(image("a.jpg", randomColor(), 100, 50));

        assertProblem(image("not an image".getBytes(StandardCharsets.UTF_8), null),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The image isn't a JPEG, PNG or WebP image");
        assertProblem(image(Arrays.copyOf(jpeg, 200), null), HttpStatus.BAD_REQUEST, "The image can't be decoded");
        assertProblem(image(Files.readAllBytes(image("large.jpg", randomColor(), 1500, 1000)), null),
                HttpStatus.BAD_REQUEST, "The image is too large, 1500 × 1000 pixels");
        assertProblem(image(jpeg, "0"), HttpStatus.BAD_REQUEST, null);
        assertProblem(mvc.post().uri("/api/search/image").multipart().exchange(), HttpStatus.BAD_REQUEST, null);
    }

    /**
     * Scans and indexes the folder; waits for the vector index to have every vector.
     *
     * @return the folder's id
     */
    private long index() {
        long id = libraries.add(photos.toString()).library().id();
        scanner.scan(id, () -> false);
        Instant deadline = Instant.now().plus(TIMEOUT);
        indexer.run(() -> libraries.get(id).progress().pending() == 0 || Instant.now().isAfter(deadline));
        await().atMost(TIMEOUT).until(() -> index.size() == embeddings.countAfter(model.id(), 0));
        return id;
    }

    private MvcTestResult text(String body) {
        return mvc.post().uri("/api/search/text").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult image(byte[] content, String limit) {
        var request = mvc.post().uri("/api/search/image").multipart()
                .file(new MockMultipartFile("image", "photo.jpg", "image/jpeg", content));
        return (limit == null ? request : request.param("limit", limit)).exchange();
    }

    private void assertProblem(MvcTestResult result, HttpStatus status, String detail) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        if (detail != null) {
            assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(detail);
        }
    }

    private Color randomColor() {
        return new Color(random.nextInt(0x1000000));
    }

    private Path image(String name, Color color, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, name.endsWith(".png") ? "png" : "jpeg", bytes)).isTrue();
        return Files.write(photos.resolve(name), bytes.toByteArray());
    }
}
