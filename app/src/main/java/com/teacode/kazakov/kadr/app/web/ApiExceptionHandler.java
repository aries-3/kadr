package com.teacode.kazakov.kadr.app.web;

import com.teacode.kazakov.kadr.core.catalog.InvalidFolderException;
import com.teacode.kazakov.kadr.core.catalog.LibraryAlreadyAddedException;
import com.teacode.kazakov.kadr.core.catalog.LibraryNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Use case errors as RFC 9457 Problem Details. Spring's own errors (validation, unknown paths)
 * are handled by Spring Boot in the same format.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler
    ProblemDetail libraryNotFound(LibraryNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail libraryAlreadyAdded(LibraryAlreadyAddedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidFolder(InvalidFolderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
