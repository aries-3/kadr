package com.teacode.kazakov.kadr.imaging;

import static com.teacode.kazakov.kadr.imaging.TestImages.colorName;
import static com.teacode.kazakov.kadr.imaging.TestImages.jpeg;
import static com.teacode.kazakov.kadr.imaging.TestImages.quadrants;
import static com.teacode.kazakov.kadr.imaging.TestImages.withOrientation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import com.teacode.kazakov.kadr.domain.images.UnsupportedImageFormatException;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageIoDecoderTest {

    /** As the shortest side to keep: the image is decoded whole. */
    private static final int WHOLE = Integer.MAX_VALUE;

    private final ImageIoDecoder decoder = new ImageIoDecoder(ImageIoDecoder.DEFAULT_MAX_PIXELS);

    @TempDir
    Path folder;

    @Test
    void decodesThePixelsAsStored() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x102030);
        image.setRGB(1, 0, 0xF0E0D0);

        RgbImage decoded = decoder.decode(write("a.png", image), WHOLE);

        assertThat(decoded.width()).isEqualTo(2);
        assertThat(decoded.height()).isEqualTo(1);
        assertThat(rgb(decoded, 0, 0)).containsExactly(0x10, 0x20, 0x30);
        assertThat(rgb(decoded, 1, 0)).containsExactly(0xF0, 0xE0, 0xD0);
    }

    /**
     * Java would brighten a grayscale image converting it to sRGB; Pillow doesn't.
     */
    @Test
    void grayscaleKeepsItsLevels() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSample(0, 0, 0, 100);

        assertThat(rgb(decoder.decode(write("a.png", image), WHOLE), 0, 0)).containsExactly(100, 100, 100);
    }

    @Test
    void sixteenBitGrayscaleKeepsTheHighByte() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setSample(0, 0, 0, 0x80FF);

        assertThat(rgb(decoder.decode(write("a.png", image), WHOLE), 0, 0)).containsExactly(0x80, 0x80, 0x80);
    }

    @Test
    void sixteenBitColorKeepsTheHighByte() throws Exception {
        ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        WritableRaster raster = model.createCompatibleWritableRaster(1, 1);
        raster.setPixel(0, 0, new int[] {0x1234, 0xABCD, 0xFF00});
        BufferedImage image = new BufferedImage(model, raster, false, null);

        assertThat(rgb(decoder.decode(write("a.png", image), WHOLE), 0, 0)).containsExactly(0x12, 0xAB, 0xFF);
    }

    @Test
    void paletteImageTakesTheColorsOfItsPalette() throws Exception {
        byte[] reds = {(byte) 200, 10};
        byte[] greens = {100, 20};
        byte[] blues = {50, (byte) 230};
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_BINARY,
                new IndexColorModel(1, 2, reds, greens, blues));
        image.getRaster().setSample(1, 0, 0, 1);

        RgbImage decoded = decoder.decode(write("a.png", image), WHOLE);

        assertThat(rgb(decoded, 0, 0)).containsExactly(200, 100, 50);
        assertThat(rgb(decoded, 1, 0)).containsExactly(10, 20, 230);
    }

    @Test
    void transparencyIsDroppedKeepingTheStoredColor() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00FF8040);
        image.setRGB(1, 0, 0x80102030);

        RgbImage decoded = decoder.decode(write("a.png", image), WHOLE);

        assertThat(rgb(decoded, 0, 0)).containsExactly(0xFF, 0x80, 0x40);
        assertThat(rgb(decoded, 1, 0)).containsExactly(0x10, 0x20, 0x30);
    }

    /**
     * Made with Pillow from known CMYK values; the expected colors are Pillow's {@code convert("RGB")}
     * of the decoded file. The file has no color profile.
     */
    @Test
    void cmykIsConvertedAsPillowDoes() throws Exception {
        int[][] pillow = {
                {255, 255, 255}, {0, 255, 255}, {191, 94, 191}, {206, 198, 190},
                {50, 140, 185}, {0, 0, 0}, {127, 126, 127}, {119, 29, 87}};

        RgbImage decoded = decoder.decode(resource("cmyk.jpg"), WHOLE);

        assertThat(decoded.width()).isEqualTo(4);
        assertThat(decoded.height()).isEqualTo(2);
        for (int i = 0; i < pillow.length; i++) {
            int[] actual = rgb(decoded, i % 4, i / 4);
            for (int channel = 0; channel < 3; channel++) {
                assertThat(actual[channel]).as("pixel %d, channel %d", i, channel)
                        .isCloseTo(pillow[i][channel], within(1));
            }
        }
    }

    @Test
    void decodesWebP() throws Exception {
        RgbImage decoded = decoder.decode(resource("halves.webp"), WHOLE);

        assertThat(colorAt(decoded, decoded.width() / 4, decoded.height() / 2)).isEqualTo("red");
        assertThat(colorAt(decoded, decoded.width() * 3 / 4, decoded.height() / 2)).isEqualTo("blue");
    }

    @Test
    void turnsThePhotoUprightByItsExifOrientation() throws Exception {
        Path file = Files.write(folder.resolve("a.jpg"), withOrientation(jpeg(quadrants(400, 200)), 6));

        RgbImage decoded = decoder.decode(file, WHOLE);

        assertThat(decoded.width()).isEqualTo(200);
        assertThat(decoded.height()).isEqualTo(400);
        int right = decoded.width() - 10;
        int bottom = decoded.height() - 10;
        assertThat(new String[] {
                colorAt(decoded, 10, 10), colorAt(decoded, right, 10),
                colorAt(decoded, 10, bottom), colorAt(decoded, right, bottom)})
                .containsExactly("blue", "red", "yellow", "green");
    }

    @Test
    void largeImageIsReducedKeepingTheShorterSide() throws Exception {
        Path file = write("a.png", quadrants(1000, 300));

        RgbImage decoded = decoder.decode(file, 100);

        // Every third pixel: 300 / 100
        assertThat(decoded.width()).isEqualTo(334);
        assertThat(decoded.height()).isEqualTo(100);
    }

    @Test
    void smallImageIsDecodedWhole() throws Exception {
        RgbImage decoded = decoder.decode(write("a.png", quadrants(150, 60)), 100);

        assertThat(decoded.width()).isEqualTo(150);
        assertThat(decoded.height()).isEqualTo(60);
    }

    @Test
    void recognizesTheFormatByContent() throws Exception {
        Path file = folder.resolve("named-as.jpg");
        ImageIO.write(quadrants(40, 20), "png", file.toFile());

        assertThat(decoder.decode(file, WHOLE).width()).isEqualTo(40);
    }

    @Test
    void anotherFileIsNotAnImage() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "not an image");

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> decoder.decode(file, WHOLE))
                .withMessageContaining("Not a JPEG, PNG or WebP image");
    }

    @Test
    void tooLargeImageIsNotDecoded() throws IOException {
        Path file = write("a.png", quadrants(20, 20));

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> new ImageIoDecoder(399).decode(file, WHOLE))
                .withMessageContaining("too large, 20 × 20 pixels");
    }

    @Test
    void missingFileCantBeOpened() {
        assertThatIOException().isThrownBy(() -> decoder.decode(folder.resolve("absent.jpg"), WHOLE));
    }

    @Test
    void decodesAnImageInMemoryUprightAsAFile() throws Exception {
        byte[] jpeg = withOrientation(jpeg(quadrants(400, 200)), 6);

        RgbImage decoded = decoder.decode(jpeg, WHOLE);

        assertThat(decoded.width()).isEqualTo(200);
        assertThat(decoded.height()).isEqualTo(400);
        assertThat(colorAt(decoded, 10, 10)).isEqualTo("blue");
        assertThat(decoded.pixels()).isEqualTo(decoder.decode(Files.write(folder.resolve("a.jpg"), jpeg), WHOLE).pixels());
    }

    @Test
    void anImageInMemoryIsReducedAsAFile() throws Exception {
        Path file = write("a.png", quadrants(1000, 300));

        assertThat(decoder.decode(Files.readAllBytes(file), 100).width()).isEqualTo(334);
    }

    @Test
    void contentOfAnotherFormatIsNotSupported() {
        assertThatExceptionOfType(UnsupportedImageFormatException.class)
                .isThrownBy(() -> decoder.decode("not an image".getBytes(StandardCharsets.UTF_8), WHOLE))
                .withMessage("Not a JPEG, PNG or WebP image");
        assertThatExceptionOfType(UnsupportedImageFormatException.class)
                .isThrownBy(() -> decoder.decode(new byte[0], WHOLE));
    }

    @Test
    void aDamagedImageInMemoryIsNotDecoded() throws IOException {
        byte[] damaged = Arrays.copyOf(jpeg(quadrants(100, 50)), 200);

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> decoder.decode(damaged, WHOLE))
                .isNotInstanceOf(UnsupportedImageFormatException.class)
                .withMessage("The image can't be decoded");
    }

    @Test
    void aTooLargeImageInMemoryIsNotDecoded() throws IOException {
        byte[] png = Files.readAllBytes(write("a.png", quadrants(20, 20)));

        assertThatExceptionOfType(UndecodableImageException.class)
                .isThrownBy(() -> new ImageIoDecoder(399).decode(png, WHOLE))
                .withMessage("The image is too large, 20 × 20 pixels");
    }

    @Test
    void theShortestSideMustBePositive() throws IOException {
        Path file = write("a.png", quadrants(20, 20));

        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(file, 0));
    }

    private Path write(String name, BufferedImage image) throws IOException {
        Path file = folder.resolve(name);
        assertThat(ImageIO.write(image, "png", file.toFile())).as("written as PNG").isTrue();
        return file;
    }

    private Path resource(String name) throws IOException {
        Path file = folder.resolve(name);
        try (InputStream image = getClass().getResourceAsStream("/images/" + name)) {
            Files.copy(Objects.requireNonNull(image), file);
        }
        return file;
    }

    private static int[] rgb(RgbImage image, int x, int y) {
        int i = (y * image.width() + x) * 3;
        byte[] pixels = image.pixels();
        return new int[] {pixels[i] & 0xFF, pixels[i + 1] & 0xFF, pixels[i + 2] & 0xFF};
    }

    private static String colorAt(RgbImage image, int x, int y) {
        int[] rgb = rgb(image, x, y);
        return colorName(rgb[0] << 16 | rgb[1] << 8 | rgb[2]);
    }
}
