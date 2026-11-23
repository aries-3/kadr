package com.teacode.kazakov.kadr.domain.enrichment;

/**
 * A task that failed an attempt.
 *
 * @param photoId the photo of the task
 * @param error   why, for the user
 * @param retry   whether another attempt may succeed: a file that couldn't be read may be readable
 *                later, one that isn't an image never will
 */
public record TaskFailure(long photoId, String error, boolean retry) {
}
