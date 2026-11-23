package com.teacode.kazakov.kadr.core.search;

/**
 * A text query that can't be searched for, e.g. a blank one.
 */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
