package com.teacode.kazakov.kadr.core.catalog;

public class LibraryNotFoundException extends RuntimeException {

    public LibraryNotFoundException(long libraryId) {
        super("Library " + libraryId + " not found");
    }
}
