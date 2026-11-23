package com.teacode.kazakov.kadr.app.config;

import com.teacode.kazakov.kadr.core.indexing.Indexer;
import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingRepository;
import com.teacode.kazakov.kadr.domain.enrichment.EnricherVersion;
import com.teacode.kazakov.kadr.domain.enrichment.EnrichmentQueue;
import com.teacode.kazakov.kadr.domain.files.FileTree;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import com.teacode.kazakov.kadr.h2.JdbcEmbeddingRepository;
import com.teacode.kazakov.kadr.h2.JdbcEnrichmentQueue;
import com.teacode.kazakov.kadr.imaging.ImageIoDecoder;
import com.teacode.kazakov.kadr.onnx.SiglipModel;
import com.teacode.kazakov.kadr.onnx.SiglipModelFiles;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Indexing: the embedding model, the task queue and the vectors.
 */
@Configuration(proxyBeanMethods = false)
class IndexingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IndexingConfiguration.class);

    /**
     * The model, loaded at startup: the service doesn't start without its files, and says which
     * are missing. Closed on shutdown, after the indexing stops.
     */
    @Bean
    EmbeddingModel embeddingModel(KadrProperties properties) {
        KadrProperties.Model model = properties.model();
        int threads = properties.indexing().effectiveInferenceThreads();
        long start = System.nanoTime();
        SiglipModel loaded = SiglipModel.load(model.id(), SiglipModelFiles.in(model.dir()), threads);
        log.info("Model {} loaded from {} in {} ms, {} threads", model.id(), model.dir(),
                (System.nanoTime() - start) / 1_000_000, threads);
        return loaded;
    }

    @Bean
    ImageDecoder imageDecoder(KadrProperties properties) {
        return new ImageIoDecoder(properties.indexing().maxMegapixels() * 1_000_000L);
    }

    @Bean
    EnrichmentQueue enrichmentQueue(JdbcTemplate jdbcTemplate, TransactionOperations transactionOperations) {
        return new JdbcEnrichmentQueue(jdbcTemplate, transactionOperations);
    }

    @Bean
    EmbeddingRepository embeddingRepository(JdbcTemplate jdbcTemplate, TransactionOperations transactionOperations) {
        return new JdbcEmbeddingRepository(jdbcTemplate, transactionOperations);
    }

    @Bean
    Indexer indexer(
            EnrichmentQueue queue,
            PhotoRepository photos,
            FileTree fileTree,
            ImageDecoder decoder,
            EmbeddingModel model,
            EmbeddingRepository embeddings,
            EnricherVersion enricher,
            Clock clock,
            KadrProperties properties) {
        KadrProperties.Indexing indexing = properties.indexing();
        return new Indexer(queue, photos, fileTree, decoder, model, embeddings, enricher, clock,
                new Indexer.Settings(indexing.decodingThreads(), indexing.batchSize(), indexing.maxAttempts(),
                        indexing.idlePause()));
    }
}
