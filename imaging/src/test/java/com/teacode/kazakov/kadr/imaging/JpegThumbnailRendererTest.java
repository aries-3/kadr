package com.teacode.kazakov.kadr.imaging;

import static com.teacode.kazakov.kadr.imaging.TestImages.colorName;
import static com.teacode.kazakov.kadr.imaging.TestImages.jpeg;
import static com.teacode.kazakov.kadr.imaging.TestImages.quadrants;
import static com.teacode.kazakov.kadr.imaging.TestImages.withOrientation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;

import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JpegThumbnailRendererTest {

    private static final int SIZE = 64;

    private final JpegThumbnailRenderer renderer =
            new JpegThumbnailRenderer(SIZE, JpegThumbnailRenderer.DEFAULT_MAX_PIXELS);

    @TempDir
    Path folder;

    @Test
    void scalesALargeImageToFit() throws Exception {
        Path file = write("a.jpg", quadrants(1000, 500), "jpeg");

        BufferedImage thumbnail = render(file);

        assertThat(thumbnail.getWidth()).isEqualTo(64);
        assertThat(thumbnail.getHeight()).isEqualTo(32);
    }

    @Test
    void doesNotEnlargeASmallImage() throws Exception {
        Path file = write("a.png", quadrants(40, 20), "png");

        BufferedImage thumbnail = render(file);

        assertThat(thumbnail.getWidth()).isEqualTo(40);
        assertThat(thumbnail.getHeight()).isEqualTo(20);
    }

    @Test
    void transparentAreasBecomeWhite() throws Exception {
        Path file = write("a.png", new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB), "png");

        assertThat(colorAt(render(file), 5, 5)).isEqualTo("white");
    }

    @Test
    void decodesWebP() throws Exception {
        Path file = folder.resolve("halves.webp");
        try (InputStream image = getClass().getResourceAsStream("/images/halves.webp")) {
            Files.copy(Objects.requireNonNull(image), file);
        }

        BufferedImage thumbnail = render(file);

        assertThat(thumbnail.getWidth()).isEqualTo(64);
        assertThat(thumbnail.getHeight()).isEqualTo(32);
        assertThat(colorAt(thumbnail, 8, 16)).isEqualTo("red");
        assertThat(colorAt(thumbnail, 56, 16)).isEqualTo("blue");
    }

    @Test
    void recognizesTheFormatByContent() throws Exception {
        Path file = write("named-as.webp", quadrants(40, 20), "png");

        assertThat(render(file).getWidth()).isEqualTo(40);
    }

    /**
     * The stored image has red, green, blue and yellow quadrants, clockwise from the top left;
     * the expected colors are those of the upright thumbnail.
     */
    @ParameterizedTest(name = "orientation {0}")
    @CsvSource({
            "1, red, green, blue, yellow",
            "2, green, red, yellow, blue",
            "3, yellow, blue, green, red",
            "4, blue, yellow, red, green",
            "5, red, blue, green, yellow",
            "6, blue, red, yellow, green",
            "7, yellow, green, blue, red",
            "8, green, yellow, red, blue",
    })
    void turnsThePhotoUprightByItsExifOrientation(
            int orientation, String topLeft, String topRight, String bottomLeft, String bottomRight)
            throws Exception {
        Path file = folder.resolve("a.jpg");
        Files.write(file, withOrientation(jpeg(quadrants(400, 200)), orientation));

        BufferedImage thumbnail = render(file);

        boolean onItsSide = orientation >= 5;
        assertThat(thumbnail.getWidth()).isEqualTo(onItsSide ? 32 : 64);
        assertThat(thumbnail.getHeight()).isEqualTo(onItsSide ? 64 : 32);
        int right = thumbnail.getWidth() - 1;
        int bottom = thumbnail.getHeight() - 1;
        assertThat(new String[] {
                colorAt(thumbnail, 4, 4),
                colorAt(thumbnail, right - 4, 4),
                colorAt(thumbnail, 4, bottom - 4),
                colorAt(thumbnail, right - 4, bottom - 4)})
                .containsExactly(topLeft, topRight, bottomLeft, bottomRight);
    }

    @Test
    void anotherFileIsNotAnImage() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "not an image");

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> renderer.render(file))
                .withMessageContaining("Not a JPEG, PNG or WebP image");
    }

    @Test
    void damagedImageIsNotDecoded() throws IOException {
        byte[] jpeg = jpeg(quadrants(100, 50));
        // The image size survives, the Huffman tables and the image data are cut off
        Path file = Files.write(folder.resolve("a.jpg"), Arrays.copyOf(jpeg, 200));

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> renderer.render(file))
                .withMessageContaining("can't be decoded");
    }

    @Test
    void tooLargeImageIsNotDecoded() throws IOException {
        Path file = write("a.png", quadrants(20, 20), "png");

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> new JpegThumbnailRenderer(SIZE, 399).render(file))
                .withMessageContaining("too large, 20 × 20 pixels");
    }

    @Test
    void missingFileCantBeOpened() {
        assertThatIOException().isThrownBy(() -> renderer.render(folder.resolve("absent.jpg")));
    }

    private BufferedImage render(Path file) throws Exception {
        byte[] jpeg = renderer.render(file);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(image).as("a JPEG").isNotNull();
        return image;
    }

    private Path write(String name, BufferedImage image, String format) throws IOException {
        Path file = folder.resolve(name);
        assertThat(ImageIO.write(image, format, file.toFile())).as("written as " + format).isTrue();
        return file;
    }

    private static String colorAt(BufferedImage image, int x, int y) {
        return colorName(image.getRGB(x, y) & 0xFFFFFF);
    }
}
