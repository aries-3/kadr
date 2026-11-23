package com.teacode.kazakov.kadr.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Turns texts into the input of the text model, as SigLIP 2 was trained: lowercased, split into
 * tokens by the model's {@code tokenizer.json}, ended by its end token, then cut or padded to
 * exactly {@value #LENGTH} tokens. The model reads its result from the last position, so without
 * the fixed padding the vectors come out different.
 * <p>
 * The tokenizer is Hugging Face's own, in Rust, through DJL. DJL is kept offline: otherwise it
 * probes the EC2 metadata address and sends telemetry when it creates a tokenizer.
 */
final class TextTokenizer implements AutoCloseable {

    /** Tokens of every text. */
    static final int LENGTH = 64;

    private final HuggingFaceTokenizer tokenizer;

    private TextTokenizer(HuggingFaceTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Loads a tokenizer.
     *
     * @param file {@code tokenizer.json}
     * @return the tokenizer
     * @throws IOException if the file can't be read
     */
    static TextTokenizer open(Path file) throws IOException {
        // Read when the tokenizer is created; also forbids downloading a native library
        System.setProperty("ai.djl.offline", "true");
        return new TextTokenizer(HuggingFaceTokenizer.builder()
                .optTokenizerPath(file)
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optPadToMaxLength()
                .optMaxLength(LENGTH)
                .build());
    }

    /**
     * Tokenizes texts.
     *
     * @param texts any texts
     * @return {@value #LENGTH} token ids for each text, one text after another
     */
    long[] ids(List<String> texts) {
        List<String> lowercased = texts.stream().map(text -> text.toLowerCase(Locale.ROOT)).toList();
        Encoding[] encodings = tokenizer.batchEncode(lowercased);
        long[] ids = new long[texts.size() * LENGTH];
        for (int i = 0; i < encodings.length; i++) {
            long[] textIds = encodings[i].getIds();
            if (textIds.length != LENGTH) {
                throw new IllegalStateException("The tokenizer made %d tokens of a text, not %d"
                        .formatted(textIds.length, LENGTH));
            }
            System.arraycopy(textIds, 0, ids, i * LENGTH, LENGTH);
        }
        return ids;
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
