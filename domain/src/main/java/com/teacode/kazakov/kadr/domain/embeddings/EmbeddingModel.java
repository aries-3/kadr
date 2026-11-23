package com.teacode.kazakov.kadr.domain.embeddings;

import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.util.List;

/**
 * A model that maps images and texts to vectors in one space: a text lands near the images it
 * describes, an image near similar images. The vectors are of unit length, so their dot product
 * is the cosine similarity.
 * <p>
 * Images and texts go in batches: a batch takes less time than its items one by one.
 * Implementations are thread-safe.
 */
public interface EmbeddingModel {

    /**
     * {@return the identifier of the model, e.g. {@code siglip2-base-patch16-224}: vectors of
     * different models can't be compared}
     */
    String id();

    /**
     * {@return the length of the vectors}
     */
    int dimension();

    /**
     * {@return the side of the square the model scales every image to, in pixels: decoding an
     * image much larger than that is wasted work}
     */
    int imageSize();

    /**
     * {@return how large to decode an image for the model: its shorter side kept at least this
     * long, in pixels}
     * <p>
     * Decoding a large photo reduced reads every n-th pixel, which keeps less detail than scaling the
     * whole photo down. Measured with SigLIP 2 on real photos, against decoding them whole, the
     * vectors keep a similarity of 0.992 on average and 0.972 at worst at twice the model's input;
     * 0.9994 and 0.998 at four times, for 20 ms more decoding a photo. So four times by default.
     */
    default int minDecodedSide() {
        return 4 * imageSize();
    }

    /**
     * Computes the vectors of images.
     *
     * @param images upright RGB images of any size
     * @return a unit vector for each image, in the same order
     */
    List<float[]> embedImages(List<RgbImage> images);

    /**
     * Computes the vectors of texts, e.g. search queries. A text longer than the model takes is
     * cut off.
     *
     * @param texts texts in any language and case
     * @return a unit vector for each text, in the same order
     */
    List<float[]> embedTexts(List<String> texts);
}
