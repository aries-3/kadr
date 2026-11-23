package com.teacode.kazakov.kadr.core.photos;

/**
 * There is no such photo, or nothing of it can be served. The message says which, for the user.
 */
public class PhotoNotFoundException extends RuntimeException {

    public PhotoNotFoundException(String message) {
        super(message);
    }
}
