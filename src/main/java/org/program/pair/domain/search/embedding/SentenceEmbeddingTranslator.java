package org.program.pair.domain.search.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Tokenise, moyenne les embeddings de tokens sur le masque d'attention (mean
 * pooling) puis normalise L2 — reproduit exactement le pipeline d'inférence
 * de sentence-transformers pour un modèle exporté en ONNX.
 */
public class SentenceEmbeddingTranslator implements Translator<String, float[]> {

    private HuggingFaceTokenizer tokenizer;

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        tokenizer = HuggingFaceTokenizer.newInstance(
            Paths.get(ctx.getModel().getModelPath().toString(), "tokenizer.json"));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        NDManager manager = ctx.getNDManager();
        Encoding encoding = tokenizer.encode(input);

        NDArray idsArray  = manager.create(encoding.getIds()).expandDims(0);
        NDArray maskArray = manager.create(encoding.getAttentionMask()).expandDims(0);
        NDArray typeArray = manager.create(encoding.getTypeIds()).expandDims(0);

        ctx.setAttachment("attentionMask", encoding.getAttentionMask());
        return new NDList(idsArray, maskArray, typeArray);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray tokenEmbeddings = list.get(0); // [1, seq_len, 384]
        long[] mask = (long[]) ctx.getAttachment("attentionMask");

        NDManager manager = ctx.getNDManager();
        NDArray maskArray = manager.create(mask)
            .toType(DataType.FLOAT32, false)
            .expandDims(0).expandDims(-1);

        NDArray masked = tokenEmbeddings.mul(maskArray);
        NDArray summed = masked.sum(new int[]{1});
        NDArray counts = maskArray.sum(new int[]{1}).clip(1e-9, Double.MAX_VALUE);
        NDArray pooled = summed.div(counts);

        // Normalisation L2 faite en Java plutôt qu'avec pow()/sum(axes, keepDims) de
        // l'NDArray : ces opérations produisent des NaN avec le moteur OnnxRuntime de
        // DJL (support d'opérateurs limité, contrairement aux moteurs PyTorch/TensorFlow).
        float[] pooledArr = pooled.toFloatArray();
        double sumSquares = 0;
        for (float v : pooledArr) {
            sumSquares += (double) v * v;
        }
        double norm = Math.max(Math.sqrt(sumSquares), 1e-9);
        float[] normalized = new float[pooledArr.length];
        for (int i = 0; i < pooledArr.length; i++) {
            normalized[i] = (float) (pooledArr[i] / norm);
        }
        return normalized;
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
