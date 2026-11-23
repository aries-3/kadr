package com.teacode.kazakov.kadr.h2;

import static org.assertj.core.api.Assertions.assertThat;

import com.teacode.kazakov.kadr.domain.catalog.CatalogFile;
import com.teacode.kazakov.kadr.domain.catalog.ContentHash;
import com.teacode.kazakov.kadr.domain.catalog.LibraryProgress;
import com.teacode.kazakov.kadr.domain.catalog.PathRange;
import com.teacode.kazakov.kadr.domain.catalog.Photo;
import com.teacode.kazakov.kadr.domain.catalog.PhotoPage;
import com.teacode.kazakov.kadr.domain.catalog.ScannedFile;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcPhotoRepositoryTest {

    private static final EnricherVersion ENRICHER = new EnricherVersion("embedding:test", 3);
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final PathRange FOLDER = PathRange.under(Path.of("/photos"));

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient sql = JdbcClient.create(db.jdbcTemplate);
    private final JdbcPhotoRepository photos = new JdbcPhotoRepository(db.jdbcTemplate, db.transactions);

    @Test
    void newFilesBecomePhotosWithPendingTasks() {
        record(file("/photos/a.jpg", 1), file("/photos/b.jpg", 2));

        assertThat(photos.files(FOLDER)).extracting(CatalogFile::path)
                .containsExactlyInAnyOrder("/photos/a.jpg", "/photos/b.jpg");
        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(2, 0, 2, 0));
        assertThat(sql.sql("SELECT DISTINCT version FROM enrichment").query(Integer.class).list())
                .containsExactly(ENRICHER.version());
    }

    @Test
    void copiesAreOnePhoto() {
        record(file("/photos/a.jpg", 1), file("/photos/copy/a.jpg", 1));

        assertThat(count("photo")).isEqualTo(1);
        assertThat(count("enrichment")).isEqualTo(1);
        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(2, 0, 1, 0));
    }

    @Test
    void copyFoundLaterJoinsTheExistingPhoto() {
        record(file("/photos/a.jpg", 1));
        long photo = photoOf("/photos/a.jpg");

        record(file("/photos/copy/a.jpg", 1));

        assertThat(photoOf("/photos/copy/a.jpg")).isEqualTo(photo);
        assertThat(count("enrichment")).isEqualTo(1);
    }

    @Test
    void changedFileIsLinkedToItsNewContent() {
        record(file("/photos/a.jpg", 1));
        CatalogFile before = photos.files(FOLDER).getFirst();
        long oldPhoto = photoOf("/photos/a.jpg");

        record(new ScannedFile("/photos/a.jpg", 200, 2000, hash(2)));

        assertThat(photos.files(FOLDER)).containsExactly(new CatalogFile(before.id(), "/photos/a.jpg", 200, 2000, false));
        assertThat(photoOf("/photos/a.jpg")).isNotEqualTo(oldPhoto);
        assertThat(count("photo")).as("the old photo is kept").isEqualTo(2);
    }

    @Test
    void processedPhotoIsNotQueuedAgain() {
        record(file("/photos/a.jpg", 1));
        sql.sql("UPDATE enrichment SET status = 'DONE'").update();

        record(file("/photos/copy/a.jpg", 1));

        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(2, 1, 0, 0));
    }

    @Test
    void anotherEnricherHasItsOwnProgress() {
        record(file("/photos/a.jpg", 1));

        assertThat(photos.progress(FOLDER, "embedding:other")).isEqualTo(new LibraryProgress(1, 0, 0, 0));
    }

    @Test
    void failedPhotosAreCounted() {
        record(file("/photos/a.jpg", 1), file("/photos/b.jpg", 2));
        sql.sql("UPDATE enrichment SET status = 'FAILED' WHERE photo_id = :id")
                .param("id", photoOf("/photos/a.jpg"))
                .update();

        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(2, 0, 1, 1));
    }

    @Test
    void rangeExcludesSiblingFolders() {
        record(file("/photos/a.jpg", 1),
                file("/photos/2023/b.jpg", 2),
                file("/photos-old/c.jpg", 3),
                file("/photos2/d.jpg", 4),
                file("/photos_backup/e.jpg", 5));

        assertThat(photos.files(FOLDER)).extracting(CatalogFile::path)
                .containsExactlyInAnyOrder("/photos/a.jpg", "/photos/2023/b.jpg");
        assertThat(photos.progress(FOLDER, ENRICHER.id()).files()).isEqualTo(2);
    }

    @Test
    void missingFilesAreNotCounted() {
        record(file("/photos/a.jpg", 1), file("/photos/b.jpg", 2));
        long a = fileId("/photos/a.jpg");

        photos.markMissing(List.of(a));

        assertThat(photos.files(FOLDER)).filteredOn(CatalogFile::missing).extracting(CatalogFile::id).containsExactly(a);
        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(1, 0, 1, 0));

        photos.markPresent(List.of(a));

        assertThat(photos.progress(FOLDER, ENRICHER.id())).isEqualTo(new LibraryProgress(2, 0, 2, 0));
    }

    @Test
    void recordingAMissingFileMakesItPresent() {
        record(file("/photos/a.jpg", 1));
        photos.markMissing(List.of(fileId("/photos/a.jpg")));

        record(file("/photos/a.jpg", 1));

        assertThat(photos.files(FOLDER)).noneMatch(CatalogFile::missing);
    }

    @Test
    void findsAPhotoWithItsPresentFiles() {
        record(file("/photos/b.jpg", 1), file("/photos/a.jpg", 1), file("/photos/c.jpg", 1));
        photos.markMissing(List.of(fileId("/photos/c.jpg")));
        long id = photoOf("/photos/a.jpg");

        assertThat(photos.findPhoto(id)).contains(
                new Photo(id, hash(1), List.of(Path.of("/photos/a.jpg"), Path.of("/photos/b.jpg"))));
    }

    @Test
    void aPhotoWithAllFilesMissingHasNoFiles() {
        record(file("/photos/a.jpg", 1));
        photos.markMissing(List.of(fileId("/photos/a.jpg")));

        assertThat(photos.findPhoto(photoOf("/photos/a.jpg"))).hasValueSatisfying(
                photo -> assertThat(photo.files()).isEmpty());
    }

    @Test
    void unknownPhotoIsNotFound() {
        assertThat(photos.findPhoto(999)).isEmpty();
    }

    @Test
    void findsThePresentFilesOfSeveralPhotos() {
        record(file("/photos/b.jpg", 1), file("/photos/a.jpg", 1), file("/photos/c.jpg", 2), file("/photos/d.jpg", 3));
        photos.markMissing(List.of(fileId("/photos/d.jpg")));
        long first = photoOf("/photos/a.jpg");
        long second = photoOf("/photos/c.jpg");
        long gone = photoOf("/photos/d.jpg");

        assertThat(photos.presentFiles(List.of(first, second, gone, 999L))).isEqualTo(Map.of(
                first, List.of(Path.of("/photos/a.jpg"), Path.of("/photos/b.jpg")),
                second, List.of(Path.of("/photos/c.jpg"))));
        assertThat(photos.presentFiles(List.of())).isEmpty();
    }

    @Test
    void listsThePhotosOfAFolderByPathPageByPage() {
        record(file("/photos/c.jpg", 3),
                file("/photos/2023/b.jpg", 2),
                file("/photos/a.jpg", 1),
                file("/photos/z/a-copy.jpg", 1),
                file("/photos-old/d.jpg", 4));

        PhotoPage first = photos.photos(FOLDER, 0, 2);
        PhotoPage second = photos.photos(FOLDER, 2, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.photos()).extracting(photo -> photo.files().getFirst().toString())
                .containsExactly("/photos/2023/b.jpg", "/photos/a.jpg");
        assertThat(first.photos().get(1).files()).as("both copies, in path order")
                .containsExactly(Path.of("/photos/a.jpg"), Path.of("/photos/z/a-copy.jpg"));
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.photos()).extracting(Photo::contentHash).containsExactly(hash(3));
        assertThat(photos.photos(FOLDER, 3, 2).photos()).isEmpty();
    }

    @Test
    void listedPhotosHaveOnlyTheirPresentFilesInsideTheFolder() {
        record(file("/photos/2023/a.jpg", 1),
                file("/photos/a-copy.jpg", 1),
                file("/elsewhere/a-copy.jpg", 1),
                file("/photos/2023/b.jpg", 2));
        photos.markMissing(List.of(fileId("/photos/a-copy.jpg"), fileId("/photos/2023/b.jpg")));

        PhotoPage page = photos.photos(PathRange.under(Path.of("/photos")), 0, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.photos()).singleElement()
                .extracting(Photo::files).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(Path.of("/photos/2023/a.jpg"));
    }

    private void record(ScannedFile... files) {
        photos.record(List.of(files), ENRICHER, NOW);
    }

    private static ScannedFile file(String path, int content) {
        return new ScannedFile(path, 100, 1000, hash(content));
    }

    private static ContentHash hash(int content) {
        return new ContentHash("%064x".formatted(content));
    }

    private long count(String table) {
        return sql.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long fileId(String path) {
        return sql.sql("SELECT id FROM photo_file WHERE path = :path").param("path", path).query(Long.class).single();
    }

    private long photoOf(String path) {
        return sql.sql("SELECT photo_id FROM photo_file WHERE path = :path").param("path", path).query(Long.class).single();
    }
}
