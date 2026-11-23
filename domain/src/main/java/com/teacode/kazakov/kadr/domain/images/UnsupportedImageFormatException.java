package com.teacode.kazakov.kadr.domain.images;

/**
 * The content isn't an image of a supported format: JPEG, PNG or WebP. Unlike a damaged image, it
 * may be fine, just not one the service reads.
 */
public class UnsupportedImageFormatException extends UndecodableImageException {

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the image, for the user
     */
    public UnsupportedImageFormatException(String message) {
        super(message);
    }
}
