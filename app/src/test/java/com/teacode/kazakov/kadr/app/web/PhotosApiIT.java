package com.teacode.kazakov.kadr.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Browsing a folder and serving photos over HTTP, on image files scanned in the background as in
 * production. Every image has a color of its own, so tests don't share photos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhotosApiIT {

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvcTester mvc;

    @TempDir
    Path temp;

    private Path photos;
    private final Random random = new Random();

    @BeforeEach
    void createPhotoFolder() throws IOException {
        photos = Files.createDirectory(temp.resolve("photos")).toRealPath();
    }

    @Test
    void listsThePhotosOfAFolderPageByPage() throws Exception {
        Path b = image("2023/b.jpg", 80, 60);
        Path a = image("a.png", 30, 20);
        Path copy = copy(a, "copies/a.png");
        long library = addAndScan();

        assertThat(mvc.get().uri("/api/libraries/{id}/photos?limit=1", library)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"total": 2, "items": [{"files": [{"path": "%s"}]}]}
                        """.formatted(b));
        assertThat(mvc.get().uri("/api/libraries/{id}/photos?offset=1", library)).hasStatusOk()
                .bodyJson().extractingPath("$.items[*].files[*].path").asArray()
                .containsExactly(a.toString(), copy.toString());
    }

    @Test
    void pageMustBeInRange() throws Exception {
        long library = addAndScan();

        for (String query : List.of("limit=0", "limit=501", "offset=-1")) {
            assertThat(mvc.get().uri("/api/libraries/{id}/photos?" + query, library))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void photosOfAnUnknownFolderAreNotFound() {
        assertProblem(mvc.get().uri("/api/libraries/999999/photos").exchange(), HttpStatus.NOT_FOUND, "999999");
    }

    @Test
    void thumbnailFitsTheSizeAndKeepsTheProportions() throws Exception {
        image("a.jpg", 1200, 800);
        long photo = onlyPhoto(addAndScan());

        MvcTestResult result = mvc.get().uri("/api/photos/{id}/thumbnail", photo).exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.IMAGE_JPEG);
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        assertThat(thumbnail.getWidth()).isEqualTo(512);
        assertThat(thumbnail.getHeight()).isEqualTo(341);
    }

    @Test
    void unchangedThumbnailIsNotSentAgain() throws Exception {
        image("a.jpg", 100, 100);
        long photo = onlyPhoto(addAndScan());

        MvcTestResult first = mvc.get().uri("/api/photos/{id}/thumbnail", photo).exchange();
        String etag = first.getResponse().getHeader("ETag");

        assertThat(etag).matches("\"[0-9a-f]{64}\"");
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(mvc.get().uri("/api/photos/{id}/thumbnail", photo).header("If-None-Match", etag))
                .hasStatus(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void thumbnailIsKeptOnceMade() throws Exception {
        Path file = image("a.jpg", 100, 100);
        long photo = onlyPhoto(addAndScan());
        assertThat(mvc.get().uri("/api/photos/{id}/thumbnail", photo)).hasStatusOk();

        Files.delete(file);

        assertThat(mvc.get().uri("/api/photos/{id}/thumbnail", photo)).hasStatusOk();
    }

    @Test
    void anotherFileOfThePhotoIsServedWhenOneIsGone() throws Exception {
        Path a = image("a.jpg", 100, 100);
        Path b = copy(a, "b.jpg");
        long photo = onlyPhoto(addAndScan());

        // Gone since the scan: the catalog still has the file as present
        Files.delete(a);

        MvcTestResult file = mvc.get().uri("/api/photos/{id}/file", photo).exchange();
        assertThat(file).hasStatusOk();
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(Files.readAllBytes(b));
        assertThat(mvc.get().uri("/api/photos/{id}/thumbnail", photo)).hasStatusOk();
    }

    @Test
    void photoWithoutReadableFilesIsNotFound() throws Exception {
        Path file = image("a.jpg", 100, 100);
        long photo = onlyPhoto(addAndScan());

        Files.delete(file);

        assertProblem(mvc.get().uri("/api/photos/{id}/file", photo).exchange(), HttpStatus.NOT_FOUND, "can be read");
        assertProblem(mvc.get().uri("/api/photos/{id}/thumbnail", photo).exchange(), HttpStatus.NOT_FOUND, "can be read");
    }

    @Test
    void fileThatIsNotAnImageHasNoThumbnail() throws Exception {
        Files.writeString(photos.resolve("notes.jpg"), "not an image " + random.nextLong());
        long photo = onlyPhoto(addAndScan());

        assertProblem(mvc.get().uri("/api/photos/{id}/thumbnail", photo).exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT, "Not a JPEG, PNG or WebP image");
    }

    @Test
    void servesTheOriginalFileWithItsType() throws Exception {
        Path file = image("a.png", 30, 20);
        long photo = onlyPhoto(addAndScan());

        MvcTestResult result = mvc.get().uri("/api/photos/{id}/file", photo).exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.IMAGE_PNG);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(Files.readAllBytes(file));
    }

    @Test
    void unknownPhotoIsNotFound() {
        assertProblem(mvc.get().uri("/api/photos/999999/file").exchange(), HttpStatus.NOT_FOUND, "999999");
        assertProblem(mvc.get().uri("/api/photos/999999/thumbnail").exchange(), HttpStatus.NOT_FOUND, "999999");
    }

    /**
     * An image of one random color, so its content is its own.
     */
    private Path image(String name, int width, int height) throws IOException {
        Path file = photos.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(random.nextInt(0x1000000)));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        String format = name.endsWith(".png") ? "png" : "jpeg";
        assertThat(ImageIO.write(image, format, file.toFile())).isTrue();
        return file;
    }

    private Path copy(Path file, String name) throws IOException {
        Path copy = photos.resolve(name);
        Files.createDirectories(copy.getParent());
        return Files.copy(file, copy);
    }

    private long addAndScan() throws Exception {
        MvcTestResult added = mvc.post().uri("/api/libraries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"%s\"}".formatted(photos))
                .exchange();
        assertThat(added).hasStatus(HttpStatus.ACCEPTED);
        long id = JsonPath.<Number>read(added.getResponse().getContentAsString(), "$.id").longValue();
        await().atMost(SCAN_TIMEOUT).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/libraries/{id}", id))
                        .bodyJson().extractingPath("$.status").isNotEqualTo("SCANNING"));
        return id;
    }

    private long onlyPhoto(long library) throws Exception {
        MvcTestResult page = mvc.get().uri("/api/libraries/{id}/photos", library).exchange();
        assertThat(page).hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(1);
        return JsonPath.<Number>read(page.getResponse().getContentAsString(), "$.items[0].id").longValue();
    }

    private void assertProblem(MvcTestResult result, HttpStatus status, String detail) {
        assertThat(result).hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").asString().contains(detail);
    }
}
