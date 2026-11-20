package com.teacode.kazakov.kadr.domain.enrichment;

/**
 * An enricher and the version of its processing. Each photo gets one task per enricher;
 * a task of an older version is redone.
 *
 * @param id      e.g. {@code embedding:siglip2-base-patch16-224}
 * @param version bumped when the processing changes, e.g. the image preprocessing
 */
public record EnricherVersion(String id, int version) {
}
