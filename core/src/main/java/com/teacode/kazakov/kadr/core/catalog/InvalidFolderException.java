package com.teacode.kazakov.kadr.core.catalog;

/**
 * The path given for a new folder can't be used. The message says why, for the user.
 */
public class InvalidFolderException extends RuntimeException {

    public InvalidFolderException(String message) {
        super(message);
    }
}
