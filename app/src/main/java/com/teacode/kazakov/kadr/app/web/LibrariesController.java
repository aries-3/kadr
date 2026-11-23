package com.teacode.kazakov.kadr.app.web;

import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.domain.catalog.LibraryOverview;
import com.teacode.kazakov.kadr.app.api.LibrariesApi;
import com.teacode.kazakov.kadr.app.api.model.AddLibraryRequest;
import com.teacode.kazakov.kadr.app.api.model.Library;
import com.teacode.kazakov.kadr.app.api.model.LibraryStatus;
import com.teacode.kazakov.kadr.app.api.model.Photo;
import com.teacode.kazakov.kadr.app.api.model.PhotoFile;
import com.teacode.kazakov.kadr.app.api.model.PhotoPage;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Folders in the index and their photos.
 */
@RestController
class LibrariesController implements LibrariesApi {

    private final LibraryService libraries;

    LibrariesController(LibraryService libraries) {
        this.libraries = libraries;
    }

    @Override
    public ResponseEntity<Library> addLibrary(AddLibraryRequest request) {
        LibraryOverview library = libraries.add(request.getPath());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PATH_GET_LIBRARY)
                .buildAndExpand(library.library().id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(toDto(library));
    }

    @Override
    public ResponseEntity<List<Library>> listLibraries() {
        return ResponseEntity.ok(libraries.list().stream().map(LibrariesController::toDto).toList());
    }

    @Override
    public ResponseEntity<Library> getLibrary(Long libraryId) {
        return ResponseEntity.ok(toDto(libraries.get(libraryId)));
    }

    @Override
    public ResponseEntity<Library> rescanLibrary(Long libraryId) {
        return ResponseEntity.accepted().body(toDto(libraries.rescan(libraryId)));
    }

    @Override
    public ResponseEntity<PhotoPage> listLibraryPhotos(Long libraryId, Integer offset, Integer limit) {
        var page = libraries.photos(libraryId, offset, limit);
        return ResponseEntity.ok(new PhotoPage(
                page.total(), page.photos().stream().map(LibrariesController::toDto).toList()));
    }

    private static Library toDto(LibraryOverview overview) {
        var library = overview.library();
        var progress = overview.progress();
        return new Library(
                library.id(),
                library.path().toString(),
                LibraryStatus.valueOf(overview.status().name()),
                progress.files(),
                progress.indexed(),
                progress.pending(),
                progress.failed())
                .scanError(library.scanError())
                .lastScanStartedAt(toOffset(library.lastScanStartedAt()))
                .lastScanFinishedAt(toOffset(library.lastScanFinishedAt()));
    }

    private static Photo toDto(com.teacode.kazakov.kadr.domain.catalog.Photo photo) {
        return new Photo(photo.id(), photo.files().stream().map(file -> new PhotoFile(file.toString())).toList());
    }

    private static @Nullable OffsetDateTime toOffset(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
