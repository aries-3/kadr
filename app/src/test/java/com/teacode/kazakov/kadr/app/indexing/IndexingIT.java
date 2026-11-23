package com.teacode.kazakov.kadr.app.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.app.FakeEmbeddingModel;
import com.teacode.kazakov.kadr.app.config.KadrProperties;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.domain.catalog.LibraryOverview;
import com.teacode.kazakov.kadr.domain.catalog.LibraryProgress;
import com.teacode.kazakov.kadr.domain.catalog.LibraryStatus;
import com.teacode.kazakov.kadr.domain.catalog.PathRange;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.EnrichmentQueue;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Indexing with the real database, queue and decoder, and the fake model. Scans and the indexer
 * run in the test thread: the indexer until the photos of the test's folder are done. Acceptance
 * criteria 1–3 of the MVP plan.
 */
@SpringBootTest
class IndexingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        // The tests run the indexer themselves
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("kadr.indexing.idle-pause", () -> "50ms");
        // Several batches from a few photos
        registry.add("kadr.indexing.batch-size", () -> "2");
    }

    /** The real model isn't loaded: see {@link FakeEmbeddingModel}. */
    @TestBean(methodName = "com.teacode.kazakov.kadr.app.FakeEmbeddingModel#create")
    EmbeddingModel model;

    @MockitoBean
    ScanLauncher launcher;

    @Autowired
    LibraryService libraries;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    Indexer indexer;

    @Autowired
    EnricherVersion enricher;

    @Autowired
    EnrichmentQueue queue;

    @Autowired
    PhotoRepository photos;

    @Autowired
    FileTree fileTree;

    @Autowired
    ImageDecoder decoder;

    @Autowired
    EmbeddingRepository embeddings;

    @Autowired
    KadrProperties properties;

    @Autowired
    JdbcClient sql;

    @TempDir
    Path temp;

    private Path folder;
    private final Random random = new Random();

    @BeforeEach
    void createFolder() throws IOException {
        folder = Files.createDirectory(temp.resolve("photos")).toRealPath();
    }

    /** Criteria 1 and 2: the folder gets ready; what isn't an image fails, with the reason. */
    @Test
    void aFolderIsIndexedAndWhatIsNotAnImageFails() throws IOException {
        image("a.jpg");
        image("b.png");
        image("2026/c.jpg");
        Files.write(folder.resolve("damaged.jpg"), Arrays.copyOf(Files.readAllBytes(image("whole.jpg")), 300));
        Files.delete(folder.resolve("whole.jpg"));
        Files.writeString(folder.resolve("text.jpg"), "not an image");
        long id = addAndScan();
        assertThat(libraries.get(id).status()).isEqualTo(LibraryStatus.INDEXING);

        index(indexer, id);

        LibraryOverview library = libraries.get(id);
        assertThat(library.status()).isEqualTo(LibraryStatus.READY);
        assertThat(library.progress()).isEqualTo(new LibraryProgress(5, 3, 0, 2));
        assertThat(errors(id)).containsOnlyKeys("damaged.jpg", "text.jpg");
        assertThat(errors(id).get("damaged.jpg")).startsWith("The image can't be decoded");
        assertThat(errors(id).get("text.jpg")).startsWith("Not a JPEG, PNG or WebP image");
        assertThat(sql.sql("""
                        SELECT e.model_id, OCTET_LENGTH(e.vector) FROM embedding e
                        JOIN photo_file f ON f.photo_id = e.photo_id
                        WHERE f.path >= :from AND f.path < :to
                        """)
                .param("from", PathRange.under(folder).from())
                .param("to", PathRange.under(folder).to())
                .query((rs, row) -> rs.getString(1) + ":" + rs.getInt(2))
                .list())
                .as("four float32 values of the fake model a photo")
                .containsExactly("fake:16", "fake:16", "fake:16");
    }

    /** Criterion 3: after a restart the interrupted photos are done, the others aren't done again. */
    @Test
    void afterARestartTheInterruptedPhotosAreDoneAndTheOthersKept() throws IOException {
        image("a.jpg");
        image("b.jpg");
        image("c.jpg");
        long id = addAndScan();
        index(indexer, id);
        Map<Long, Long> before = seqs(id);
        long interrupted = before.keySet().iterator().next();
        // As a process stopped in the middle of a batch leaves it: taken, no vector yet
        sql.sql("DELETE FROM embedding WHERE photo_id = :id").param("id", interrupted).update();
        sql.sql("UPDATE enrichment SET status = 'RUNNING' WHERE photo_id = :id").param("id", interrupted).update();
        assertThat(libraries.get(id).progress()).isEqualTo(new LibraryProgress(3, 2, 1, 0));

        index(indexer, id);

        Map<Long, Long> after = seqs(id);
        assertThat(libraries.get(id).progress()).isEqualTo(new LibraryProgress(3, 3, 0, 0));
        assertThat(after).containsKey(interrupted);
        before.forEach((photo, seq) -> {
            if (photo != interrupted) {
                assertThat(after.get(photo)).as("photo %d is not done again", photo).isEqualTo(seq);
            }
        });
    }

    @Test
    void aNewVersionOfTheProcessingDoesEveryPhotoAgain() throws IOException {
        image("a.jpg");
        image("b.jpg");
        long id = addAndScan();
        index(indexer, id);
        Map<Long, Long> before = seqs(id);

        try {
            index(indexer(new EnricherVersion(enricher.id(), enricher.version() + 1), Clock.systemUTC()), id);

            Map<Long, Long> after = seqs(id);
            before.forEach((photo, seq) -> assertThat(after.get(photo)).isGreaterThan(seq));
        } finally {
            // Back to the configured version for the other tests
            index(indexer, id);
        }
    }

    @Test
    void aPhotoThatCanNotBeReadIsTriedAgainAndThenFails() throws IOException {
        Path gone = image("a.jpg");
        long id = addAndScan();
        Files.delete(gone);
        // Every reading of the clock is two minutes later, so a retry is due at once
        Clock impatient = new Clock() {
            private Instant now = Instant.now();

            @Override
            public synchronized Instant instant() {
                now = now.plus(Duration.ofMinutes(2));
                return now;
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };

        index(indexer(enricher, impatient), id);

        assertThat(libraries.get(id).progress()).isEqualTo(new LibraryProgress(1, 0, 0, 1));
        assertThat(sql.sql("""
                        SELECT e.attempts FROM enrichment e JOIN photo_file f ON f.photo_id = e.photo_id
                        WHERE f.path = :path
                        """).param("path", gone.toString()).query(Integer.class).single())
                .isEqualTo(properties.indexing().maxAttempts());
        assertThat(errors(id).get("a.jpg")).isEqualTo("Can't read " + gone);
    }

    @Test
    void aPhotoIsReadFromAnotherOfItsFiles() throws IOException {
        Path first = image("a.jpg");
        Files.createDirectory(folder.resolve("copy"));
        Files.copy(first, folder.resolve("copy/a.jpg"));
        long id = addAndScan();
        Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("---------"));

        index(indexer, id);

        assertThat(libraries.get(id).progress()).isEqualTo(new LibraryProgress(2, 1, 0, 0));
    }

    /**
     * Runs an indexer in this thread until no photo of the folder is pending.
     */
    private void index(Indexer indexer, long libraryId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        indexer.run(() -> libraries.get(libraryId).progress().pending() == 0 || Instant.now().isAfter(deadline));
        assertThat(libraries.get(libraryId).progress().pending()).as("pending after %s", TIMEOUT).isZero();
    }

    private Indexer indexer(EnricherVersion version, Clock clock) {
        KadrProperties.Indexing settings = properties.indexing();
        return new Indexer(queue, photos, fileTree, decoder, model, embeddings, version, clock,
                new Indexer.Settings(settings.decodingThreads(), settings.batchSize(), settings.maxAttempts(),
                        settings.idlePause()));
    }

    private long addAndScan() {
        long id = libraries.add(folder.toString()).library().id();
        scanner.scan(id, () -> false);
        return id;
    }

    /**
     * An image of one random color, so its content is its own.
     */
    private Path image(String name) throws IOException {
        Path file = folder.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(random.nextInt(0x1000000)));
        graphics.fillRect(0, 0, 64, 48);
        graphics.dispose();
        assertThat(ImageIO.write(image, name.endsWith(".png") ? "png" : "jpeg", file.toFile())).isTrue();
        return file;
    }

    /**
     * The errors of the failed photos of the folder, by the path of their file relative to it.
     */
    private Map<String, String> errors(long libraryId) {
        PathRange range = libraries.get(libraryId).library().files();
        return sql.sql("""
                        SELECT f.path, e.error FROM enrichment e JOIN photo_file f ON f.photo_id = e.photo_id
                        WHERE e.status = 'FAILED' AND f.path >= :from AND f.path < :to
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .query((rs, row) -> Map.entry(folder.relativize(Path.of(rs.getString(1))).toString(), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The write number of the vector of each photo of the folder.
     */
    private Map<Long, Long> seqs(long libraryId) {
        PathRange range = libraries.get(libraryId).library().files();
        return sql.sql("""
                        SELECT DISTINCT e.photo_id, e.seq FROM embedding e JOIN photo_file f ON f.photo_id = e.photo_id
                        WHERE f.path >= :from AND f.path < :to ORDER BY e.photo_id
                        """)
                .param("from", range.from())
                .param("to", range.to())
                .query((rs, row) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
