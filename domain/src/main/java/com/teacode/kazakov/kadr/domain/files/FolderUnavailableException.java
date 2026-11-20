package com.teacode.kazakov.kadr.domain.files;

/**
 * A folder doesn't exist, isn't a folder or can't be listed. The message says which, for the user.
 */
public class FolderUnavailableException extends Exception {

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the folder, for the user
     */
    public FolderUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what is wrong with the folder, for the user
     * @param cause   the error that showed it
     */
    public FolderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
