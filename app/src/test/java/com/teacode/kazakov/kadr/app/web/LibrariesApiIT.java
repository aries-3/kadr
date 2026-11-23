package com.teacode.kazakov.kadr.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
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
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The libraries API over HTTP, with scans running in the background as in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibrariesApiIT {

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        // Photos stay pending, so the scans can be checked as they leave them
        registry.add("kadr.indexing.enabled", () -> "false");
    }

    /** The real model isn't loaded: see {@link com.teacode.kazakov.kadr.app.FakeEmbeddingModel}. */
    @TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
    EmbeddingModel model;

    @Autowired
    MockMvcTester mvc;

    @TempDir
    Path temp;

    private Path photos;

    @BeforeEach
    void createPhotos() throws IOException {
        photos = Files.createDirectory(temp.resolve("photos")).toRealPath();
        Random random = new Random();
        for (String name : new String[] {"a.jpg", "2023/b.png"}) {
            Path file = photos.resolve(name);
            Files.createDirectories(file.getParent());
            byte[] content = new byte[2048];
            random.nextBytes(content);
            Files.write(file, content);
        }
    }

    @Test
    void addedFolderIsScannedInTheBackground() throws UnsupportedEncodingException {
        MvcTestResult added = add(photos.toString());

        assertThat(added).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.path").isEqualTo(photos.toString());
        long id = id(added);
        assertThat(added.getResponse().getHeader("Location")).isEqualTo("http://localhost/api/libraries/" + id);

        awaitScanned(id);
        assertThat(mvc.get().uri("/api/libraries/{id}", id)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"status": "INDEXING", "files": 2, "indexed": 0, "pending": 2, "failed": 0}
                """);
    }

    @Test
    void listShowsAddedFolders() throws UnsupportedEncodingException {
        long id = id(add(photos.toString()));
        awaitScanned(id);

        assertThat(mvc.get().uri("/api/libraries")).hasStatusOk()
                .bodyJson().extractingPath("$[?(@.id == %d)].path".formatted(id)).asArray()
                .containsExactly(photos.toString());
    }

    @Test
    void rescanIsAccepted() throws UnsupportedEncodingException {
        long id = id(add(photos.toString()));
        awaitScanned(id);

        assertThat(mvc.post().uri("/api/libraries/{id}/rescan", id)).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.id").asNumber().isEqualTo((int) id);
        awaitScanned(id);
    }

    @Test
    void sameFolderIsAddedOnce() throws UnsupportedEncodingException {
        awaitScanned(id(add(photos.toString())));

        assertProblem(add(photos + "/2023/.."), HttpStatus.CONFLICT, "already added");
    }

    @Test
    void pathMustBeAbsolute() {
        assertProblem(add("photos/2023"), HttpStatus.BAD_REQUEST, "must be absolute");
    }

    @Test
    void folderMustExist() {
        assertProblem(add(photos + "/absent"), HttpStatus.BAD_REQUEST, "doesn't exist");
    }

    @Test
    void pathMustBeAFolder() {
        assertProblem(add(photos + "/a.jpg"), HttpStatus.BAD_REQUEST, "Not a folder");
    }

    @Test
    void pathIsRequired() {
        assertThat(post("{}")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(post("{\"path\": \"\"}")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unknownLibraryIsNotFound() {
        assertProblem(mvc.get().uri("/api/libraries/999999").exchange(), HttpStatus.NOT_FOUND, "999999");
        assertProblem(mvc.post().uri("/api/libraries/999999/rescan").exchange(), HttpStatus.NOT_FOUND, "999999");
    }

    @Test
    void libraryIdIsANumber() {
        assertThat(mvc.get().uri("/api/libraries/abc")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private MvcTestResult add(String path) {
        return post("{\"path\": \"%s\"}".formatted(path));
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri("/api/libraries").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static long id(MvcTestResult result) throws UnsupportedEncodingException {
        return JsonPath.<Number>read(result.getResponse().getContentAsString(), "$.id").longValue();
    }

    private void awaitScanned(long id) {
        await().atMost(SCAN_TIMEOUT).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/libraries/{id}", id))
                        .bodyJson().extractingPath("$.status").isNotEqualTo("SCANNING"));
    }

    private void assertProblem(MvcTestResult result, HttpStatus status, String detail) {
        assertThat(result).hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").asString().contains(detail);
    }
}
