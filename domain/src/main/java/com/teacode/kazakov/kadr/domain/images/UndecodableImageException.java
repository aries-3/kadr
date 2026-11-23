package com.teacode.kazakov.kadr.domain.images;

/**
 * The content of a file can't be decoded as an image: it isn't in a supported format, is damaged
 * or has too many pixels. The message says which, for the user.
 */
public class UndecodableImageException extends Exception {

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the image, for the user
     */
    public UndecodableImageException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what is wrong with the image, for the user
     * @param cause   the decoder's error
     */
    public UndecodableImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
