package com.teacode.kazakov.kadr.app.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.teacode.kazakov.kadr.app.RealModel;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.domain.catalog.LibraryProgress;
import com.teacode.kazakov.kadr.domain.catalog.LibraryStatus;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Indexing with the real model, as the service runs it: from photo files to vectors in the
 * database. Needs the model files, which aren't in the repository: from the folder in the
 * {@code kadr.model.dir} system property, by default where the service looks for them. Skipped
 * without them, or without memory enough for the vision tower; the text tower isn't loaded.
 */
@SpringBootTest
// Closed after the class: the model takes gigabytes
@DirtiesContext
class RealModelIndexingIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @TempDir
    static Path dataDir;

    @BeforeAll
    static void modelIsThere() throws IOException {
        // The vision tower only
        RealModel.assumeAvailable(1536);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kadr.data-dir", dataDir::toString);
        registry.add("kadr.model.dir", RealModel.FOLDER::toString);
        // The test runs the indexer itself
        registry.add("kadr.indexing.enabled", () -> "false");
        registry.add("kadr.indexing.idle-pause", () -> "50ms");
    }

    @MockitoBean
    ScanLauncher launcher;

    @Autowired
    LibraryService libraries;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    Indexer indexer;

    @Autowired
    EmbeddingModel model;

    @Autowired
    ImageDecoder decoder;

    @Autowired
    JdbcClient sql;

    @TempDir
    Path folder;

    @Test
    void storesTheVectorsTheModelMakesOfThePhotos() throws Exception {
        List<Path> images = List.of(image("a.jpg", 1200, 800), image("b.png", 300, 400), image("c.jpg", 2000, 1500));
        Files.writeString(folder.resolve("broken.jpg"), "not an image");
        long id = libraries.add(folder.toRealPath().toString()).library().id();
        scanner.scan(id, () -> false);

        Instant deadline = Instant.now().plus(TIMEOUT);
        indexer.run(() -> libraries.get(id).progress().pending() == 0 || Instant.now().isAfter(deadline));

        assertThat(libraries.get(id).status()).isEqualTo(LibraryStatus.READY);
        assertThat(libraries.get(id).progress()).isEqualTo(new LibraryProgress(4, 3, 0, 1));
        for (Path image : images) {
            float[] stored = stored(image.toRealPath());
            float[] expected = model.embedImages(List.of(decoder.decode(image, model.minDecodedSide()))).getFirst();
            assertThat(stored).as(image.getFileName().toString()).hasSize(768);
            double similarity = 0;
            for (int i = 0; i < stored.length; i++) {
                similarity += stored[i] * expected[i];
            }
            assertThat(similarity).as(image.getFileName().toString()).isCloseTo(1.0, within(1e-5));
        }
    }

    private float[] stored(Path file) {
        byte[] bytes = sql.sql("""
                        SELECT e.vector FROM embedding e JOIN photo_file f ON f.photo_id = e.photo_id
                        WHERE f.path = :path AND e.model_id = :model
                        """)
                .param("path", file.toString())
                .param("model", model.id())
                .query(byte[].class)
                .single();
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    /**
     * Shapes of random colors on a random background.
     */
    private Path image(String name, int width, int height) throws IOException {
        Random random = new Random(name.hashCode());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(random.nextInt(0x1000000)));
        graphics.fillRect(0, 0, width, height);
        for (int i = 0; i < 20; i++) {
            graphics.setColor(new Color(random.nextInt(0x1000000)));
            graphics.fillOval(random.nextInt(width), random.nextInt(height), width / 5, height / 5);
        }
        graphics.dispose();
        Path file = folder.resolve(name);
        assertThat(ImageIO.write(image, name.endsWith(".png") ? "png" : "jpeg", file.toFile())).isTrue();
        return file;
    }
}
