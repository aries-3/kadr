package com.teacode.kazakov.kadr.lucene;

import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vector index on Lucene: a document a photo, with its id and its vector in an HNSW graph,
 * compared by dot product, which for unit vectors is the cosine.
 * <p>
 * Every commit records the model, the dimension, the layout of the documents and the write number
 * of the last vector added. An index that can't be read or records something else is made anew,
 * empty, to be filled from the catalog.
 * <p>
 * Thread-safe: vectors are added by one thread, searches run on any. Added vectors are found after
 * the readers are refreshed, which happens at most once a second.
 */
public final class LuceneVectorIndex implements VectorIndex, AutoCloseable {

    /** The layout of the documents: changing it makes indexes of the old one unusable. */
    static final int SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(LuceneVectorIndex.class);

    /** The photo id as a term, to replace the document of a photo. */
    private static final String ID = "id";
    /** The photo id as a number, to read it from a found document. */
    private static final String PHOTO_ID = "photo_id";
    private static final String VECTOR = "vector";

    private static final String MODEL_KEY = "modelId";
    private static final String DIMENSION_KEY = "dimension";
    private static final String SCHEMA_KEY = "schemaVersion";
    private static final String SEQ_KEY = "lastSeq";

    private static final long REFRESH_INTERVAL_NANOS = 1_000_000_000L;

    private final Directory directory;
    private final boolean ownsDirectory;
    private final String modelId;
    private final int dimension;
    private final IndexWriter writer;
    private final SearcherManager searchers;

    private volatile long durableSeq;
    private volatile long addedSeq;
    private volatile boolean stale;
    private volatile long refreshedAt = System.nanoTime();

    private LuceneVectorIndex(Directory directory, boolean ownsDirectory, String modelId, int dimension,
            IndexWriter writer, long durableSeq) throws IOException {
        this.directory = directory;
        this.ownsDirectory = ownsDirectory;
        this.modelId = modelId;
        this.dimension = dimension;
        this.writer = writer;
        this.searchers = new SearcherManager(writer, null);
        this.durableSeq = durableSeq;
        this.addedSeq = durableSeq;
    }

    /**
     * Opens the index in a folder, made anew if it is missing, can't be read or was made for
     * another model, dimension or layout. Then it is empty and says it has nothing, so it is
     * filled from the catalog.
     *
     * @param folder    the folder of the index, created if missing
     * @param modelId   the model whose vectors it holds
     * @param dimension the length of the vectors
     * @return the index
     * @throws IOException if the folder can't be used, e.g. another process holds the index
     */
    public static LuceneVectorIndex open(Path folder, String modelId, int dimension) throws IOException {
        Files.createDirectories(folder);
        return open(FSDirectory.open(folder), true, modelId, dimension);
    }

    /**
     * Opens the index in a Lucene directory; see {@link #open(Path, String, int)}.
     *
     * @param ownsDirectory whether closing the index closes the directory
     */
    static LuceneVectorIndex open(Directory directory, boolean ownsDirectory, String modelId, int dimension)
            throws IOException {
        try {
            Optional<String> unusable = unusable(directory, modelId, dimension);
            if (unusable.isEmpty()) {
                try {
                    long seq = Long.parseLong(SegmentInfos.readLatestCommit(directory).getUserData().get(SEQ_KEY));
                    IndexWriter writer = new IndexWriter(directory, config(IndexWriterConfig.OpenMode.APPEND));
                    return new LuceneVectorIndex(directory, ownsDirectory, modelId, dimension, writer, seq);
                } catch (LockObtainFailedException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    unusable = Optional.of("it can't be opened: " + e);
                }
            }
            if (DirectoryReader.indexExists(directory)) {
                log.warn("The vector index of {} is made anew, as {}", modelId, unusable.orElseThrow());
            }
            clear(directory);
            IndexWriter writer = new IndexWriter(directory, config(IndexWriterConfig.OpenMode.CREATE));
            return new LuceneVectorIndex(directory, ownsDirectory, modelId, dimension, writer, 0);
        } catch (IOException | RuntimeException e) {
            if (ownsDirectory) {
                directory.close();
            }
            throw e;
        }
    }

    /**
     * {@return why the index in the directory can't be used; empty if it can}
     */
    private static Optional<String> unusable(Directory directory, String modelId, int dimension) {
        Map<String, String> data;
        try {
            if (!DirectoryReader.indexExists(directory)) {
                return Optional.of("there is none");
            }
            data = SegmentInfos.readLatestCommit(directory).getUserData();
        } catch (IOException | RuntimeException e) {
            return Optional.of("it can't be read: " + e);
        }
        if (!modelId.equals(data.get(MODEL_KEY))) {
            return Optional.of("it is of the model " + data.get(MODEL_KEY));
        }
        if (!String.valueOf(dimension).equals(data.get(DIMENSION_KEY))) {
            return Optional.of("its vectors are of length " + data.get(DIMENSION_KEY) + ", not " + dimension);
        }
        if (!String.valueOf(SCHEMA_VERSION).equals(data.get(SCHEMA_KEY))) {
            return Optional.of("its layout is of version " + data.get(SCHEMA_KEY) + ", not " + SCHEMA_VERSION);
        }
        if (data.get(SEQ_KEY) == null) {
            return Optional.of("it doesn't say what it has");
        }
        return Optional.empty();
    }

    /**
     * Deletes the files of an index, holding its write lock: the index of another process is left
     * alone. Lucene would read a damaged index even to replace it.
     */
    private static void clear(Directory directory) throws IOException {
        try (Lock lock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
            for (String file : directory.listAll()) {
                if (!file.equals(IndexWriter.WRITE_LOCK_NAME)) {
                    directory.deleteFile(file);
                }
            }
        }
    }

    private static IndexWriterConfig config(IndexWriterConfig.OpenMode mode) {
        // One merge thread: indexing photos takes the cores already
        ConcurrentMergeScheduler merges = new ConcurrentMergeScheduler();
        merges.setMaxMergesAndThreads(2, 1);
        return new IndexWriterConfig().setOpenMode(mode).setMergeScheduler(merges);
    }

    @Override
    public long durableSeq() {
        return durableSeq;
    }

    @Override
    public void upsert(List<PhotoEmbedding> embeddings, long lastSeq) {
        try {
            for (PhotoEmbedding embedding : embeddings) {
                if (embedding.vector().length != dimension) {
                    throw new IllegalArgumentException("A vector of length %d in an index of length %d"
                            .formatted(embedding.vector().length, dimension));
                }
                String id = Long.toString(embedding.photoId());
                Document document = new Document();
                document.add(new StringField(ID, id, Field.Store.NO));
                document.add(new NumericDocValuesField(PHOTO_ID, embedding.photoId()));
                document.add(new KnnFloatVectorField(VECTOR, embedding.vector(), VectorSimilarityFunction.DOT_PRODUCT));
                writer.updateDocument(new Term(ID, id), document);
            }
            addedSeq = lastSeq;
            stale = true;
            refreshIfDue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void commit() {
        long seq = addedSeq;
        try {
            writer.setLiveCommitData(Map.of(
                    MODEL_KEY, modelId,
                    DIMENSION_KEY, String.valueOf(dimension),
                    SCHEMA_KEY, String.valueOf(SCHEMA_VERSION),
                    SEQ_KEY, String.valueOf(seq)).entrySet());
            writer.commit();
            durableSeq = seq;
            stale = false;
            refreshedAt = System.nanoTime();
            searchers.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<VectorHit> search(float[] query, int limit) {
        try {
            refreshIfDue();
            IndexSearcher searcher = searchers.acquire();
            try {
                ScoreDoc[] found = searcher.search(new KnnFloatVectorQuery(VECTOR, query, limit), limit).scoreDocs;
                List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
                List<VectorHit> hits = new ArrayList<>(found.length);
                for (ScoreDoc doc : found) {
                    LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(doc.doc, leaves));
                    NumericDocValues ids = leaf.reader().getNumericDocValues(PHOTO_ID);
                    if (ids != null && ids.advanceExact(doc.doc - leaf.docBase)) {
                        // Lucene scores a dot product as (1 + dot) / 2, to keep it positive
                        hits.add(new VectorHit(ids.longValue(), 2.0 * doc.score - 1));
                    }
                }
                return hits;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int size() {
        return writer.getDocStats().numDocs;
    }

    /**
     * Opens new readers for the searches if vectors were added, at most once a second: each
     * refresh writes what was added as a new segment.
     */
    private void refreshIfDue() throws IOException {
        if (stale && System.nanoTime() - refreshedAt >= REFRESH_INTERVAL_NANOS) {
            stale = false;
            refreshedAt = System.nanoTime();
            searchers.maybeRefresh();
        }
    }

    /**
     * Commits what was added and closes the index.
     */
    @Override
    public void close() throws IOException {
        try {
            if (addedSeq != durableSeq) {
                commit();
            }
            searchers.close();
            writer.close();
        } finally {
            if (ownsDirectory) {
                directory.close();
            }
        }
    }

    /**
     * Closes the index dropping what wasn't committed, as a crash would. For tests.
     */
    void abandon() throws IOException {
        searchers.close();
        writer.rollback();
    }
}
