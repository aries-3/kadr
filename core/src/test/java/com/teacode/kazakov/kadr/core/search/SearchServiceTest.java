package com.teacode.kazakov.kadr.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teacode.kazakov.kadr.domain.catalog.PhotoRepository;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.embeddings.PhotoEmbedding;
import com.teacode.kazakov.kadr.domain.embeddings.VectorHit;
import com.teacode.kazakov.kadr.domain.embeddings.VectorIndex;
import com.teacode.kazakov.kadr.domain.images.ImageDecoder;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import com.teacode.kazakov.kadr.domain.images.UndecodableImageException;
import com.teacode.kazakov.kadr.domain.images.UnsupportedImageFormatException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private static final float[] QUERY = {1, 0};

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final ImageDecoder decoder = mock(ImageDecoder.class);
    private final PhotoRepository photos = mock(PhotoRepository.class);
    private final ExactIndex index = new ExactIndex();
    private final Map<Long, List<Path>> files = new HashMap<>();
    /** Asks the index for at least 4: few photos make it ask again. */
    private final SearchService search = new SearchService(model, decoder, index, photos, 4);

    @BeforeEach
    void setUp() {
        when(model.embedTexts(any())).thenReturn(List.of(QUERY));
        when(model.minDecodedSide()).thenReturn(896);
        when(photos.presentFiles(anyCollection())).thenAnswer(call -> {
            Map<Long, List<Path>> present = new HashMap<>();
            for (Object id : (Collection<?>) call.getArgument(0)) {
                List<Path> found = files.get((Long) id);
                if (found != null) {
                    present.put((Long) id, found);
                }
            }
            return present;
        });
    }

    @Test
    void findsTheNearestPhotosWithTheirFiles() {
        photos(10);

        List<FoundPhoto> found = search.byText("a dog", 3);

        assertThat(found).extracting(FoundPhoto::photoId).containsExactly(1L, 2L, 3L);
        assertThat(found.getFirst().files()).containsExactly(Path.of("/photos/1.jpg"));
        assertThat(found).extracting(FoundPhoto::score).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(index.asked).containsExactly(6);
    }

    @Test
    void photosWhoseFilesAreGoneAreLeftOutAndMoreAreAskedFor() {
        photos(10);
        Set.of(1L, 2L, 3L, 4L, 5L, 6L).forEach(files::remove);

        List<FoundPhoto> found = search.byText("a dog", 3);

        assertThat(found).extracting(FoundPhoto::photoId).containsExactly(7L, 8L, 9L);
        assertThat(index.asked).as("twice the limit, then four times more").containsExactly(6, 24);
    }

    @Test
    void anIndexWithFewerPhotosGivesWhatItHas() {
        photos(2);

        assertThat(search.byText("a dog", 5)).extracting(FoundPhoto::photoId).containsExactly(1L, 2L);
        assertThat(index.asked).containsExactly(10);
    }

    @Test
    void aBlankQueryIsRefused() {
        assertThatExceptionOfType(InvalidQueryException.class).isThrownBy(() -> search.byText("  \n", 5));
    }

    @Test
    void anImageIsDecodedAsForIndexing() throws Exception {
        photos(3);
        RgbImage image = new RgbImage(1, 1, new byte[3]);
        when(decoder.decode(any(byte[].class), anyInt())).thenReturn(image);
        when(model.embedImages(List.of(image))).thenReturn(List.of(QUERY));

        assertThat(search.byImage(new byte[] {1}, 1)).extracting(FoundPhoto::photoId).containsExactly(1L);
        verify(decoder).decode(new byte[] {1}, 896);
    }

    @Test
    void anImageOfAnotherFormatIsNotSupported() throws Exception {
        when(decoder.decode(any(byte[].class), anyInt())).thenThrow(new UnsupportedImageFormatException("Not a JPEG"));

        assertThatExceptionOfType(UnsupportedImageException.class).isThrownBy(() -> search.byImage(new byte[1], 5));
    }

    @Test
    void aDamagedImageIsInvalid() throws Exception {
        when(decoder.decode(any(byte[].class), anyInt())).thenThrow(new UndecodableImageException("The image can't be decoded"));

        assertThatExceptionOfType(InvalidImageException.class).isThrownBy(() -> search.byImage(new byte[1], 5))
                .withMessage("The image can't be decoded");
    }

    /**
     * Photos 1, 2, 3… each with a file, nearer to the query the lower the number.
     */
    private void photos(int count) {
        List<PhotoEmbedding> embeddings = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            double angle = id * 0.1;
            embeddings.add(new PhotoEmbedding(id, new float[] {(float) Math.cos(angle), (float) Math.sin(angle)}));
            files.put(id, List.of(Path.of("/photos/%d.jpg".formatted(id))));
        }
        index.upsert(embeddings, count);
    }

    /**
     * Compares the query with every vector, and remembers how many photos it was asked for.
     */
    private static final class ExactIndex implements VectorIndex {

        final List<Integer> asked = new ArrayList<>();
        private final List<PhotoEmbedding> embeddings = new ArrayList<>();

        @Override
        public long durableSeq() {
            return 0;
        }

        @Override
        public void upsert(List<PhotoEmbedding> added, long lastSeq) {
            embeddings.addAll(added);
        }

        @Override
        public void commit() {
        }

        @Override
        public List<VectorHit> search(float[] query, int limit) {
            asked.add(limit);
            return embeddings.stream()
                    .map(e -> new VectorHit(e.photoId(), query[0] * e.vector()[0] + query[1] * e.vector()[1]))
                    .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public int size() {
            return embeddings.size();
        }
    }
}
