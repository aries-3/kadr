package com.teacode.kazakov.kadr.imaging;

import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import com.teacode.kazakov.kadr.domain.images.UnsupportedImageFormatException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Reads image files with ImageIO: JPEG and WebP by the TwelveMonkeys plugins, PNG by the JDK.
 * The format is recognized by the content, not by the file name.
 * <p>
 * The size is read from the header first: an image with too many pixels isn't decoded at all.
 * A large image is decoded at a reduced resolution, reading every n-th pixel of every n-th row,
 * so it takes little memory and time.
 */
final class ImageReading {

    /**
     * Chooses how much to reduce an image while decoding.
     */
    @FunctionalInterface
    interface Subsampling {

        /**
         * @param width  of the stored image
         * @param height of the stored image
         * @return n to read every n-th pixel of every n-th row, 1 to read all
         */
        int step(int width, int height);
    }

    private ImageReading() {
    }

    /**
     * Registers the ImageIO plugins on the class path. ImageIO looks for plugins once, with the
     * class loader of the thread that first uses it; in an executable jar that may miss the
     * plugins in the nested jars. Called where the application creates its image services.
     */
    static void registerPlugins() {
        ImageIO.scanForPlugins();
    }

    /**
     * Reads an image file.
     *
     * @param file        the file
     * @param maxPixels   images with more pixels aren't decoded
     * @param subsampling how much to reduce the image
     * @return the image as decoded, not turned upright
     * @throws IOException                     if the file can't be opened
     * @throws UnsupportedImageFormatException if the content isn't of a supported format
     * @throws UndecodableImageException       if the image is damaged or has too many pixels
     */
    static BufferedImage read(Path file, long maxPixels, Subsampling subsampling)
            throws IOException, UndecodableImageException {
        try (ImageInputStream input = new FileImageInputStream(file.toFile())) {
            return read(input, file.toString(), maxPixels, subsampling);
        }
    }

    /**
     * Reads an image held in memory.
     *
     * @param image       the content of an image file
     * @param maxPixels   images with more pixels aren't decoded
     * @param subsampling how much to reduce the image
     * @return the image as decoded, not turned upright
     * @throws UnsupportedImageFormatException if the content isn't of a supported format
     * @throws UndecodableImageException       if the image is damaged or has too many pixels
     */
    static BufferedImage read(byte[] image, long maxPixels, Subsampling subsampling)
            throws UndecodableImageException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            return read(input, null, maxPixels, subsampling);
        } catch (IOException e) {
            // Closing a stream in memory: not expected
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param name the file, for the messages; none for an image in memory
     */
    private static BufferedImage read(ImageInputStream input, @Nullable String name, long maxPixels,
            Subsampling subsampling) throws UndecodableImageException {
        Iterator<ImageReader> readers;
        try {
            readers = ImageIO.getImageReaders(input);
        } catch (IllegalArgumentException e) {
            throw new UndecodableImageException(about("The image can't be decoded", name), e);
        }
        if (!readers.hasNext()) {
            throw new UnsupportedImageFormatException(about("Not a JPEG, PNG or WebP image", name));
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if ((long) width * height > maxPixels) {
                throw new UndecodableImageException(
                        about("The image is too large, %d × %d pixels".formatted(width, height), name));
            }
            ImageReadParam param = reader.getDefaultReadParam();
            int step = subsampling.step(width, height);
            param.setSourceSubsampling(step, step, 0, 0);
            return reader.read(0, param);
        } catch (IOException | RuntimeException e) {
            // The source is open, so what fails is its content; decoders throw runtime
            // exceptions on some damaged files
            throw new UndecodableImageException(about("The image can't be decoded", name), e);
        } finally {
            reader.dispose();
        }
    }

    private static String about(String message, @Nullable String name) {
        return name == null ? message : message + ": " + name;
    }
}
