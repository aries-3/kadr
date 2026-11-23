package com.teacode.kazakov.kadr.imaging;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Images made for tests.
 */
final class TestImages {

    private static final Map<String, Color> COLORS = Map.of(
            "red", Color.RED, "green", Color.GREEN, "blue", Color.BLUE, "yellow", Color.YELLOW);

    private TestImages() {
    }

    /**
     * Red, green, blue and yellow quadrants, clockwise from the top left.
     */
    static BufferedImage quadrants(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, halfWidth, halfHeight);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(halfWidth, 0, width - halfWidth, halfHeight);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, halfHeight, halfWidth, height - halfHeight);
        graphics.setColor(Color.YELLOW);
        graphics.fillRect(halfWidth, halfHeight, width - halfWidth, height - halfHeight);
        graphics.dispose();
        return image;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", bytes);
        return bytes.toByteArray();
    }

    /**
     * Adds an EXIF segment with the orientation tag after the JFIF segment the JDK writes.
     */
    static byte[] withOrientation(byte[] jpeg, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(26)
                .put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0)
                .putInt(0);
        byte[] header = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int length = 2 + header.length + tiff.capacity();
        int app0End = 4 + ((jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, app0End);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes(header);
        out.writeBytes(tiff.array());
        out.write(jpeg, app0End, jpeg.length - app0End);
        return out.toByteArray();
    }

    /**
     * The nearest of red, green, blue, yellow, white: JPEG shifts colors a little.
     *
     * @param rgb a color as {@code 0xRRGGBB}
     */
    static String colorName(int rgb) {
        Color pixel = new Color(rgb);
        String nearest = "white";
        double best = distance(pixel, Color.WHITE);
        for (Map.Entry<String, Color> color : COLORS.entrySet()) {
            double distance = distance(pixel, color.getValue());
            if (distance < best) {
                best = distance;
                nearest = color.getKey();
            }
        }
        return nearest;
    }

    private static double distance(Color a, Color b) {
        int red = a.getRed() - b.getRed();
        int green = a.getGreen() - b.getGreen();
        int blue = a.getBlue() - b.getBlue();
        return Math.sqrt(red * red + green * green + blue * blue);
    }
}
