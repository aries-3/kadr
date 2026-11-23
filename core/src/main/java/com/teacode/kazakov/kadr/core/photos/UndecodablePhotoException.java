package com.teacode.kazakov.kadr.core.photos;

/**
 * A photo's file can't be decoded as an image, so it has no thumbnail. The message says why, for
 * the user.
 */
public class UndecodablePhotoException extends RuntimeException {

    public UndecodablePhotoException(String message, Throwable cause) {
        super(message, cause);
    }
}
