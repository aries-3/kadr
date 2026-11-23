package com.teacode.kazakov.kadr.imaging;

import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

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
     * @throws IOException               if the file can't be opened
     * @throws UndecodableImageException if the content isn't a supported image, is damaged or has
     *                                   too many pixels
     */
    static BufferedImage read(Path file, long maxPixels, Subsampling subsampling)
            throws IOException, UndecodableImageException {
        try (ImageInputStream input = new FileImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UndecodableImageException("Not a JPEG, PNG or WebP image: " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > maxPixels) {
                    throw new UndecodableImageException(
                            "The image is too large, %d × %d pixels: %s".formatted(width, height, file));
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling.step(width, height);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } catch (IOException | RuntimeException e) {
                // The file is open, so what fails is its content; decoders throw runtime
                // exceptions on some damaged files
                throw new UndecodableImageException("The image can't be decoded: " + file, e);
            } finally {
                reader.dispose();
            }
        }
    }
}
