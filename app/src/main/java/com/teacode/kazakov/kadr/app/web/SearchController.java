package com.teacode.kazakov.kadr.app.web;

import com.teacode.kazakov.kadr.app.api.SearchApi;
import com.teacode.kazakov.kadr.app.api.model.PhotoFile;
import com.teacode.kazakov.kadr.app.api.model.SearchHit;
import com.teacode.kazakov.kadr.app.api.model.SearchResult;
import com.teacode.kazakov.kadr.app.api.model.TextSearchRequest;
import com.teacode.kazakov.kadr.core.search.FoundPhoto;
import com.teacode.kazakov.kadr.core.search.SearchService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Semantic search by text and by image.
 */
@RestController
class SearchController implements SearchApi {

    /** As the contract says when no limit is given. */
    private static final int DEFAULT_LIMIT = 20;

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @Override
    public ResponseEntity<SearchResult> searchByText(TextSearchRequest request) {
        return ResponseEntity.ok(result(search.byText(request.getQuery(), limit(request.getLimit()))));
    }

    @Override
    public ResponseEntity<SearchResult> searchByImage(MultipartFile image, @Nullable Integer limit) {
        byte[] content;
        try {
            content = image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok(result(search.byImage(content, limit(limit))));
    }

    private static int limit(@Nullable Integer limit) {
        return limit != null ? limit : DEFAULT_LIMIT;
    }

    private SearchResult result(List<FoundPhoto> found) {
        return new SearchResult(search.modelId(), found.stream()
                .map(photo -> new SearchHit(photo.photoId(), photo.score(),
                        photo.files().stream().map(file -> new PhotoFile(file.toString())).toList()))
                .toList());
    }
}
