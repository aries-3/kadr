package com.teacode.kazakov.kadr.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.teacode.kazakov.kadr.domain.embeddings.EmbeddingModel;
import com.teacode.kazakov.kadr.domain.images.RgbImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A SigLIP 2 model on ONNX Runtime, on the CPU. It has two towers, one for images and one for
 * texts, trained to map into one space. Each tower is an ONNX graph whose {@code pooler_output}
 * is the vector; the vectors are then scaled to unit length.
 * <p>
 * Images are prepared as the Hugging Face image processor does, texts as SigLIP 2 was trained;
 * a mistake there gives no error, just worse vectors, so the results were checked against the
 * reference implementation in Python.
 */
public final class SiglipModel implements EmbeddingModel, AutoCloseable {

    private static final String OUTPUT = "pooler_output";
    private static final String TEXT_INPUT = "input_ids";

    private final String id;
    private final ImagePreprocessor preprocessor;
    private final TextTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions options;
    private final OrtSession vision;
    private final OrtSession text;
    private final String visionInput;
    private final int dimension;

    private SiglipModel(String id, ImagePreprocessor preprocessor, TextTokenizer tokenizer,
            OrtSession.SessionOptions options, OrtSession vision, OrtSession text) throws OrtException {
        this.id = id;
        this.preprocessor = preprocessor;
        this.tokenizer = tokenizer;
        this.environment = OrtEnvironment.getEnvironment();
        this.options = options;
        this.vision = vision;
        this.text = text;
        this.visionInput = checkVisionInput(vision, preprocessor.size());
        checkTextInput(text);
        this.dimension = outputDimension(vision, "vision");
        int textDimension = outputDimension(text, "text");
        if (textDimension != dimension) {
            throw new IllegalStateException("The vision model makes vectors of %d values, the text model of %d"
                    .formatted(dimension, textDimension));
        }
    }

    /**
     * Loads a model: reads its settings and tokenizer, starts an ONNX Runtime session for each
     * tower and checks their inputs and outputs. Takes a few seconds and about 1.5 times the size
     * of the model files in memory, for a moment more: 2 GB for the base model, 2.6 GB at the peak.
     *
     * @param id      the identifier of the model, e.g. {@code siglip2-base-patch16-224}
     * @param files   its files
     * @param threads how many threads one inference may use
     * @return the model
     * @throws IllegalStateException if a file is missing or can't be loaded, or the model isn't a
     *                               SigLIP one this class can run
     */
    public static SiglipModel load(String id, SiglipModelFiles files, int threads) {
        List<Path> missing = files.missing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("The files of the model %s are missing: %s. "
                    + "Put them there; the README says where to get them")
                    .formatted(id, missing.stream().map(Path::toString).collect(Collectors.joining(", "))));
        }
        ImagePreprocessor preprocessor = ImagePreprocessor.read(files.preprocessorConfig());
        List<AutoCloseable> opened = new ArrayList<>();
        try {
            TextTokenizer tokenizer = TextTokenizer.open(files.tokenizer());
            opened.add(tokenizer);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            opened.add(options);
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            // The text tower first: loading takes about twice its file size for a moment (2 GB
            // for the base model), so it's better done before the vision tower takes its share
            OrtSession text = environment.createSession(files.textModel().toString(), options);
            opened.add(text);
            OrtSession vision = environment.createSession(files.visionModel().toString(), options);
            opened.add(vision);
            return new SiglipModel(id, preprocessor, tokenizer, options, vision, text);
        } catch (IOException | OrtException | RuntimeException e) {
            for (AutoCloseable resource : opened.reversed()) {
                try {
                    resource.close();
                } catch (Exception closing) {
                    e.addSuppressed(closing);
                }
            }
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Can't load the model %s: %s".formatted(id, e.getMessage()), e);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public int imageSize() {
        return preprocessor.size();
    }

    @Override
    public List<float[]> embedImages(List<RgbImage> images) {
        if (images.isEmpty()) {
            return List.of();
        }
        int size = preprocessor.size();
        FloatBuffer input = ByteBuffer.allocateDirect(images.size() * 3 * size * size * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (RgbImage image : images) {
            preprocessor.write(image, input);
        }
        input.rewind();
        try {
            return run(vision, visionInput,
                    OnnxTensor.createTensor(environment, input, new long[] {images.size(), 3, size, size}));
        } catch (OrtException e) {
            throw new IllegalStateException("Can't embed images: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        LongBuffer input = LongBuffer.wrap(tokenizer.ids(texts));
        try {
            return run(text, TEXT_INPUT,
                    OnnxTensor.createTensor(environment, input, new long[] {texts.size(), TextTokenizer.LENGTH}));
        } catch (OrtException e) {
            throw new IllegalStateException("Can't embed texts: " + e.getMessage(), e);
        }
    }

    private List<float[]> run(OrtSession session, String inputName, OnnxTensor input) throws OrtException {
        try (input; OrtSession.Result result = session.run(Map.of(inputName, input), Set.of(OUTPUT))) {
            OnnxTensor output = (OnnxTensor) result.get(OUTPUT).orElseThrow();
            FloatBuffer values = output.getFloatBuffer();
            int count = values.remaining() / dimension;
            List<float[]> vectors = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                float[] vector = new float[dimension];
                values.get(vector);
                vectors.add(unitLength(vector));
            }
            return vectors;
        }
    }

    private static float[] unitLength(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        float length = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }

    /**
     * The vision tower takes a batch of images as floats: {@code [batch, 3, size, size]}.
     *
     * @return the name of its input
     */
    private static String checkVisionInput(OrtSession vision, int size) throws OrtException {
        Map<String, NodeInfo> inputs = vision.getInputInfo();
        TensorInfo tensor = inputs.size() == 1 ? tensor(inputs.values().iterator().next()) : null;
        long[] shape = tensor != null ? tensor.getShape() : new long[0];
        if (tensor == null || tensor.type != OnnxJavaType.FLOAT || shape.length != 4
                || !fits(shape[1], 3) || !fits(shape[2], size) || !fits(shape[3], size)) {
            throw new IllegalStateException(
                    "The vision model must take images as one float tensor [batch, 3, %d, %d], it takes %s"
                            .formatted(size, size, inputs));
        }
        return inputs.keySet().iterator().next();
    }

    /**
     * The text tower takes a batch of token ids only, without an attention mask: SigLIP was
     * trained without one.
     */
    private static void checkTextInput(OrtSession text) throws OrtException {
        Map<String, NodeInfo> inputs = text.getInputInfo();
        TensorInfo tensor = inputs.size() == 1 && inputs.containsKey(TEXT_INPUT) ? tensor(inputs.get(TEXT_INPUT)) : null;
        long[] shape = tensor != null ? tensor.getShape() : new long[0];
        if (tensor == null || tensor.type != OnnxJavaType.INT64 || shape.length != 2
                || !fits(shape[1], TextTokenizer.LENGTH)) {
            throw new IllegalStateException(
                    "The text model must take only %s as an int64 tensor [batch, %d], it takes %s"
                            .formatted(TEXT_INPUT, TextTokenizer.LENGTH, inputs));
        }
    }

    /**
     * Each tower puts out {@code pooler_output}: a vector for each item of the batch.
     *
     * @return the length of the vectors
     */
    private static int outputDimension(OrtSession session, String tower) throws OrtException {
        Map<String, NodeInfo> outputs = session.getOutputInfo();
        TensorInfo tensor = tensor(outputs.get(OUTPUT));
        long[] shape = tensor != null ? tensor.getShape() : new long[0];
        if (tensor == null || tensor.type != OnnxJavaType.FLOAT || shape.length != 2 || shape[1] < 1) {
            throw new IllegalStateException("The %s model must put out %s as a float tensor [batch, dimension], it puts out %s"
                    .formatted(tower, OUTPUT, outputs));
        }
        return Math.toIntExact(shape[1]);
    }

    private static @Nullable TensorInfo tensor(@Nullable NodeInfo node) {
        return node != null && node.getInfo() instanceof TensorInfo tensor ? tensor : null;
    }

    /**
     * A dimension of a tensor shape: -1 if it may be of any size.
     */
    private static boolean fits(long dimension, long expected) {
        return dimension == expected || dimension < 0;
    }

    @Override
    public void close() {
        try {
            text.close();
            vision.close();
        } catch (OrtException e) {
            throw new IllegalStateException("Can't close the model: " + e.getMessage(), e);
        } finally {
            options.close();
            tokenizer.close();
        }
    }
}
