package com.teacode.kazakov.kadr.domain.catalog;

import java.util.List;

/**
 * A page of the photos inside a folder.
 *
 * @param total  photos with a present file inside the folder, on all pages
 * @param photos photos of this page; each has only its present files inside the folder
 */
public record PhotoPage(long total, List<Photo> photos) {
}
