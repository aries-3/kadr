package com.teacode.kazakov.kadr.imaging;

import com.teacode.kazakov.kadr.domain.images.ThumbnailRenderer;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Thumbnails as JPEG, decoded with ImageIO: JPEG and WebP by the TwelveMonkeys plugins, PNG by the
 * JDK. The format is recognized by the content, not by the file name.
 * <p>
 * A large photo is decoded at a reduced resolution, about twice the thumbnail size, so it takes
 * little memory and time. Then it is scaled down in halving steps, turned upright as its EXIF
 * orientation says, and transparent areas become white.
 */
public final class JpegThumbnailRenderer implements ThumbnailRenderer {

    /** Images with more pixels aren't decoded: about a 14000 × 14000 photo. */
    public static final long DEFAULT_MAX_PIXELS = 200_000_000L;

    private static final float JPEG_QUALITY = 0.85f;

    private final int size;
    private final long maxPixels;

    /**
     * @param size      longest side of a thumbnail, in pixels; smaller images aren't enlarged
     * @param maxPixels images with more pixels aren't decoded
     */
    public JpegThumbnailRenderer(int size, long maxPixels) {
        this.size = size;
        this.maxPixels = maxPixels;
        // ImageIO looks for plugins once, with the class loader of the thread that first uses it;
        // in an executable jar that may miss the plugins in the nested jars
        ImageIO.scanForPlugins();
    }

    @Override
    public byte[] render(Path file) throws IOException, UndecodableImageException {
        BufferedImage image = decode(file);
        return encode(scale(image, Orientation.of(file)));
    }

    private BufferedImage decode(Path file) throws IOException, UndecodableImageException {
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
                // Reads every n-th pixel, keeping at least twice the thumbnail size
                int step = Math.max(1, Math.max(width, height) / (2 * size));
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

    private BufferedImage scale(BufferedImage image, Orientation orientation) {
        double ratio = Math.min(1.0, (double) size / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        BufferedImage current = image;
        // One bilinear step skips pixels when shrinking more than twice: halving keeps the detail even
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2, Orientation.NORMAL);
        }
        return draw(current, width, height, orientation);
    }

    /**
     * Draws an image scaled to the given size and turned upright, as RGB.
     */
    private static BufferedImage draw(BufferedImage source, int width, int height, Orientation orientation) {
        boolean swaps = orientation.swapsSides();
        BufferedImage target = new BufferedImage(
                swaps ? height : width, swaps ? width : height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.transform(orientation.upright(width, height));
            // JPEG has no transparency: transparent areas become white
            graphics.drawImage(source, 0, 0, width, height, Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            // Written to memory: not expected
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
