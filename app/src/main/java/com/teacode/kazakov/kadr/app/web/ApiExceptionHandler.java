package com.teacode.kazakov.kadr.app.web;

import com.teacode.kazakov.kadr.core.catalog.InvalidFolderException;
import com.teacode.kazakov.kadr.core.catalog.LibraryAlreadyAddedException;
import com.teacode.kazakov.kadr.core.catalog.LibraryNotFoundException;
import com.teacode.kazakov.kadr.core.photos.PhotoNotFoundException;
import com.teacode.kazakov.kadr.core.photos.UndecodablePhotoException;
import com.teacode.kazakov.kadr.core.search.InvalidImageException;
import com.teacode.kazakov.kadr.core.search.InvalidQueryException;
import com.teacode.kazakov.kadr.core.search.UnsupportedImageException;
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

    @ExceptionHandler
    ProblemDetail photoNotFound(PhotoNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail undecodablePhoto(UndecodablePhotoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidQuery(InvalidQueryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidImage(InvalidImageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail unsupportedImage(UnsupportedImageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }
}
