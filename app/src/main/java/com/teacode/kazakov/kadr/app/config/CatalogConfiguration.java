package com.teacode.kazakov.kadr.app.config;

import com.teacode.kazakov.kadr.fs.LocalFileTree;
import com.teacode.kazakov.kadr.fs.Sha256ContentHasher;
import com.teacode.kazakov.kadr.h2.JdbcLibraryRepository;
import com.teacode.kazakov.kadr.h2.JdbcPhotoRepository;
import com.teacode.kazakov.kadr.core.catalog.LibraryScanner;
import com.teacode.kazakov.kadr.core.catalog.LibraryService;
import com.teacode.kazakov.kadr.core.catalog.ScanLauncher;
import com.teacode.kazakov.kadr.domain.catalog.LibraryRepository;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.files.ContentHasher;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The catalog: folders, scanning, photos and their files.
 */
@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    /**
     * Version of the embedding processing: bumping it indexes every photo again. Bump it when the
     * vectors change: the model files, decoding, preparing images for the model.
     */
    static final int EMBEDDING_VERSION = 1;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The enricher whose tasks make up the indexing progress: embeddings of the configured model.
     */
    @Bean
    EnricherVersion embeddingEnricher(KadrProperties properties) {
        return new EnricherVersion("embedding:" + properties.model().id(), EMBEDDING_VERSION);
    }

    @Bean
    LibraryRepository libraryRepository(JdbcClient jdbcClient) {
        return new JdbcLibraryRepository(jdbcClient);
    }

    @Bean
    PhotoRepository photoRepository(JdbcTemplate jdbcTemplate, TransactionOperations transactionOperations) {
        return new JdbcPhotoRepository(jdbcTemplate, transactionOperations);
    }

    @Bean
    FileTree fileTree() {
        return new LocalFileTree();
    }

    @Bean
    ContentHasher contentHasher() {
        return new Sha256ContentHasher();
    }

    @Bean
    LibraryScanner libraryScanner(
            LibraryRepository libraries,
            PhotoRepository photos,
            FileTree fileTree,
            ContentHasher hasher,
            EnricherVersion enricher,
            Clock clock) {
        return new LibraryScanner(
                libraries, photos, fileTree, hasher, enricher, clock, LibraryScanner.DEFAULT_BATCH_SIZE);
    }

    @Bean
    LibraryService libraryService(
            LibraryRepository libraries,
            PhotoRepository photos,
            FileTree fileTree,
            ScanLauncher scans,
            EnricherVersion enricher,
            Clock clock) {
        return new LibraryService(libraries, photos, fileTree, scans, enricher, clock);
    }

    @Bean
    ApplicationRunner resumeInterruptedScans(LibraryService libraries) {
        return args -> libraries.resumeInterruptedScans();
    }
}
