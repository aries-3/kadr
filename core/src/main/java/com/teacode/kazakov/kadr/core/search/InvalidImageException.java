package com.teacode.kazakov.kadr.core.search;

/**
 * An image to search by that is damaged or has too many pixels.
 */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
