package com.teacode.kazakov.kadr.core.search;

import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import com.teacode.kazakov.kadr.domain.images.UnsupportedImageFormatException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds photos by meaning: by a text that describes them, or by an image like them. The query is
 * turned into a vector by the model; the vector index finds the nearest photos; the catalog gives
 * their files.
 * <p>
 * A photo whose files are all gone since the last scan is left out. The index is asked for more
 * photos than wanted, so that leaving some out still leaves enough; when it doesn't, the index is
 * asked again for more.
 */
public final class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** The fewest photos asked of the index: asking for more makes the approximate search more exact. */
    static final int MIN_CANDIDATES = 100;
    /** Asking for more than this many, the search stops trying to fill the limit. */
    static final int MAX_CANDIDATES = 10_000;

    private final EmbeddingModel model;
    private final ImageDecoder decoder;
    private final VectorIndex index;
    private final PhotoRepository photos;
    private final int minCandidates;

    public SearchService(EmbeddingModel model, ImageDecoder decoder, VectorIndex index, PhotoRepository photos) {
        this(model, decoder, index, photos, MIN_CANDIDATES);
    }

    SearchService(EmbeddingModel model, ImageDecoder decoder, VectorIndex index, PhotoRepository photos,
            int minCandidates) {
        this.model = model;
        this.decoder = decoder;
        this.index = index;
        this.photos = photos;
        this.minCandidates = minCandidates;
    }

    /**
     * {@return the identifier of the model the searches use}
     */
    public String modelId() {
        return model.id();
    }

    /**
     * Finds photos a text describes.
     *
     * @param query what to look for, in any language
     * @param limit the most photos to return
     * @return the photos, the most similar first
     * @throws InvalidQueryException if the query is blank
     */
    public List<FoundPhoto> byText(String query, int limit) {
        if (query.isBlank()) {
            throw new InvalidQueryException("The query is blank");
        }
        long start = System.nanoTime();
        float[] vector = model.embedTexts(List.of(query)).getFirst();
        List<FoundPhoto> found = find(vector, limit);
        log.debug("Searched by a text of {} characters in {} ms: {} photos", query.length(),
                (System.nanoTime() - start) / 1_000_000, found.size());
        return found;
    }

    /**
     * Finds photos like an image. The image is decoded and prepared as photos are for indexing,
     * and isn't kept.
     *
     * @param image the content of a JPEG, PNG or WebP file
     * @param limit the most photos to return
     * @return the photos, the most similar first
     * @throws UnsupportedImageException if the content isn't of a supported format
     * @throws InvalidImageException     if the image is damaged or has too many pixels
     */
    public List<FoundPhoto> byImage(byte[] image, int limit) {
        long start = System.nanoTime();
        RgbImage decoded;
        try {
            decoded = decoder.decode(image, model.minDecodedSide());
        } catch (UnsupportedImageFormatException e) {
            throw new UnsupportedImageException("The image isn't a JPEG, PNG or WebP image", e);
        } catch (UndecodableImageException e) {
            throw new InvalidImageException(Objects.requireNonNullElse(e.getMessage(), "The image can't be decoded"), e);
        }
        float[] vector = model.embedImages(List.of(decoded)).getFirst();
        List<FoundPhoto> found = find(vector, limit);
        log.debug("Searched by an image of {} × {} in {} ms: {} photos", decoded.width(), decoded.height(),
                (System.nanoTime() - start) / 1_000_000, found.size());
        return found;
    }

    private List<FoundPhoto> find(float[] vector, int limit) {
        int candidates = Math.max(2 * limit, minCandidates);
        while (true) {
            List<VectorHit> hits = index.search(vector, candidates);
            Map<Long, List<Path>> files = photos.presentFiles(hits.stream().map(VectorHit::photoId).toList());
            List<FoundPhoto> found = new ArrayList<>(Math.min(limit, hits.size()));
            for (VectorHit hit : hits) {
                List<Path> present = files.get(hit.photoId());
                if (present != null) {
                    found.add(new FoundPhoto(hit.photoId(), hit.score(), present));
                    if (found.size() == limit) {
                        return found;
                    }
                }
            }
            // The index has no more, or asking for more costs too much
            if (hits.size() < candidates || candidates >= MAX_CANDIDATES) {
                return found;
            }
            candidates = Math.min(4 * candidates, MAX_CANDIDATES);
        }
    }
}
