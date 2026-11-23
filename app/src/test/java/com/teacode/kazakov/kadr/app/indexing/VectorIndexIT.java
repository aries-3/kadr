package com.teacode.kazakov.kadr.app.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.teacode.kazakov.kadr.app.FakeEmbeddingModel;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.core.indexing.VectorIndexUpdater;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.StoredEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import com.teacode.kazakov.kadr.lucene.LuceneVectorIndex;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The vector index following the catalog, with the real database and Lucene and the fake model.
 * The service's index is kept up by its background updater; the tests make indexes of their own in
 * empty folders, as after deleting {@code index/}, and run an updater on them in the test thread.
 * Acceptance criterion 5 of the MVP plan.
 */
@SpringBootTest
class VectorIndexIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final VectorIndexUpdater.Settings FAST =
            new VectorIndexUpdater.Settings(100, Duration.ofMillis(20), Duration.ofMinutes(1), 10_000);

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        // The tests run the indexer themselves
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("kadr.indexing.idle-pause", () -> "50ms");
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
    VectorIndex index;

    @Autowired
    EmbeddingRepository embeddings;

    @TempDir
    Path temp;

    private final Random random = new Random();

    @Test
    void theIndexOfTheServiceFollowsTheCatalog() throws IOException {
        indexPhotos("a", 3);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(index.size()).isEqualTo(total()));
        for (PhotoEmbedding photo : stored()) {
            assertThat(index.search(photo.vector(), 1)).singleElement().satisfies(hit -> {
                assertThat(hit.photoId()).isEqualTo(photo.photoId());
                assertThat(hit.score()).isCloseTo(1.0, within(1e-5));
            });
        }
    }

    /** Criterion 5: an index deleted while the service is stopped is made again from the catalog. */
    @Test
    void aLostIndexIsMadeAgainFromTheCatalogAndFindsTheSame(@TempDir Path folder) throws IOException {
        indexPhotos("b", 4);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(index.size()).isEqualTo(total()));

        try (LuceneVectorIndex rebuilt = LuceneVectorIndex.open(folder, model.id(), model.dimension())) {
            assertThat(rebuilt.durableSeq()).isZero();
            follow(rebuilt);

            assertThat(rebuilt.size()).isEqualTo(index.size());
            for (PhotoEmbedding photo : stored()) {
                List<VectorHit> expected = index.search(photo.vector(), 3);
                List<VectorHit> actual = rebuilt.search(photo.vector(), 3);
                assertThat(actual).extracting(VectorHit::photoId)
                        .containsExactlyElementsOf(expected.stream().map(VectorHit::photoId).toList());
            }
        }
    }

    /** After a restart the index takes from the catalog only what was written after its last commit. */
    @Test
    void afterARestartOnlyWhatCameLaterIsAdded(@TempDir Path folder) throws IOException {
        indexPhotos("c", 2);
        try (LuceneVectorIndex own = LuceneVectorIndex.open(folder, model.id(), model.dimension())) {
            follow(own);
        }
        indexPhotos("d", 3);

        try (LuceneVectorIndex own = LuceneVectorIndex.open(folder, model.id(), model.dimension())) {
            assertThat(embeddings.countAfter(model.id(), own.durableSeq())).isEqualTo(3);
            follow(own);

            assertThat(own.size()).isEqualTo(total());
        }
    }

    /**
     * Runs an updater on an index until it has every vector of the catalog.
     */
    private void follow(VectorIndex own) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        new VectorIndexUpdater(embeddings, own, model.id(), FAST)
                .run(() -> own.size() >= total() || Instant.now().isAfter(deadline));
        assertThat(own.size()).isEqualTo(total());
    }

    /**
     * Makes photos of random colors in a folder of their own, scans and indexes them.
     */
    private void indexPhotos(String name, int count) throws IOException {
        Path folder = Files.createDirectory(temp.resolve(name)).toRealPath();
        for (int i = 0; i < count; i++) {
            BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(new Color(random.nextInt(0x1000000)));
            graphics.fillRect(0, 0, 40, 30);
            graphics.dispose();
            assertThat(ImageIO.write(image, "png", folder.resolve(i + ".png").toFile())).isTrue();
        }
        long id = libraries.add(folder.toString()).library().id();
        scanner.scan(id, () -> false);
        Instant deadline = Instant.now().plus(TIMEOUT);
        indexer.run(() -> libraries.get(id).progress().pending() == 0 || Instant.now().isAfter(deadline));
        assertThat(libraries.get(id).progress().indexed()).isEqualTo(count);
    }

    private long total() {
        return embeddings.countAfter(model.id(), 0);
    }

    private List<PhotoEmbedding> stored() {
        return embeddings.after(model.id(), 0, 1000).stream().map(StoredEmbedding::embedding).toList();
    }
}
