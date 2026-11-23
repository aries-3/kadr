package com.teacode.kazakov.kadr.core.search;

/**
 * An image to search by that isn't of a supported format: JPEG, PNG or WebP.
 */
public class UnsupportedImageException extends RuntimeException {

    public UnsupportedImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
