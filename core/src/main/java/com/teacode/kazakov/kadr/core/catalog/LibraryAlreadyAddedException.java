package com.teacode.kazakov.kadr.core.catalog;

import java.nio.file.Path;

public class LibraryAlreadyAddedException extends RuntimeException {

    public LibraryAlreadyAddedException(Path folder) {
        super("The folder is already added: " + folder);
    }
}
