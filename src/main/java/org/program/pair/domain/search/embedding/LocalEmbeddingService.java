package org.program.pair.domain.search.embedding;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Génère des embeddings de phrase localement (ONNX/DJL, modèle
 * paraphrase-multilingual-MiniLM-L12-v2, 384 dimensions, trilingue FR/EN/DE),
 * remplaçant l'appel payant à l'API OpenAI. Le modèle est téléchargé une
 * seule fois au premier démarrage depuis {@code meetdo.embedding.model-base-url}
 * vers {@code meetdo.embedding.model-path} (un volume persistant en
 * production), puis réutilisé aux démarrages suivants.
 * <p>
 * Conçu "fail-soft" : un échec de téléchargement/chargement du modèle ne
 * fait jamais planter le démarrage de l'application — le prédicteur reste
 * simplement {@code null} et {@link #generateEmbedding} retombe sur un
 * vecteur nul, détectable via {@link #isZeroVector}, pour que les appelants
 * puissent basculer sur la recherche plein texte.
 */
@Service
@Slf4j
public class LocalEmbeddingService {

    private static final int EMBEDDING_DIMENSION = 384;
    private static final int MAX_INPUT_LENGTH = 2000;
    private static final List<String> MODEL_FILES = List.of(
        "model_quantized.onnx", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json");

    @Value("${meetdo.embedding.enabled:true}")
    private boolean enabled;

    @Value("${meetdo.embedding.model-path:${user.home}/.cache/meetdo-embeddings}")
    private String modelPath;

    @Value("${meetdo.embedding.model-base-url:https://github.com/Mibrai/pair_backend/releases/download/models-v1}")
    private String modelBaseUrl;

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private final Object predictLock = new Object();

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Modèle d'embeddings local désactivé (meetdo.embedding.enabled=false)");
            return;
        }

        try {
            Path modelDir = Paths.get(modelPath);
            Files.createDirectories(modelDir);

            boolean missingFile = MODEL_FILES.stream()
                .anyMatch(f -> !Files.exists(modelDir.resolve(f)));
            if (missingFile) {
                log.info("Modèle d'embeddings absent de {} — téléchargement initial depuis {}...",
                    modelDir, modelBaseUrl);
                downloadModelFiles(modelDir);
            }

            log.info("Chargement du modèle d'embeddings local (ONNX, FR/EN/DE)...");
            long start = System.currentTimeMillis();

            Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelDir)
                .optModelName("model_quantized")
                .optEngine("OnnxRuntime")
                .optTranslator(new SentenceEmbeddingTranslator())
                .build();

            this.model = criteria.loadModel();
            this.predictor = model.newPredictor();

            log.info("Modèle d'embeddings chargé en {} ms", System.currentTimeMillis() - start);
        } catch (IOException | ModelNotFoundException | MalformedModelException e) {
            log.error("Échec du chargement du modèle d'embeddings local — la recherche sémantique " +
                "retombera sur la recherche plein texte : {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }

    /**
     * false si {@code meetdo.embedding.enabled=false} (profil de test) — permet
     * aux composants qui génèrent des embeddings en masse au démarrage (les
     * seeders) de s'auto-désactiver proprement, y compris quand ce service est
     * mocké dans un test d'intégration (un booléen non-stubé renvoie false par
     * défaut chez Mockito, ce qui évite de polluer l'historique d'appels du mock).
     */
    public boolean isEnabled() {
        return enabled;
    }

    public float[] generateEmbedding(String text) {
        if (predictor == null || text == null || text.isBlank()) {
            return new float[EMBEDDING_DIMENSION];
        }
        String truncated = text.length() > MAX_INPUT_LENGTH ? text.substring(0, MAX_INPUT_LENGTH) : text;
        try {
            synchronized (predictLock) {
                return predictor.predict(truncated);
            }
        } catch (TranslateException e) {
            log.error("Échec génération embedding local : {}", e.getMessage());
            return new float[EMBEDDING_DIMENSION];
        }
    }

    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    public int getDimension() {
        return EMBEDDING_DIMENSION;
    }

    /** Sentinelle "échec de génération" : un vrai embedding est L2-normalisé, donc jamais nul. */
    public static boolean isZeroVector(float[] embedding) {
        if (embedding == null) return true;
        for (float v : embedding) {
            if (v != 0f) return false;
        }
        return true;
    }

    private void downloadModelFiles(Path targetDir) throws IOException {
        for (String filename : MODEL_FILES) {
            Path finalPath = targetDir.resolve(filename);
            Path tempPath = targetDir.resolve(filename + ".tmp");
            URL url = URI.create(modelBaseUrl + "/" + filename).toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }
            Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Modèle téléchargé et persisté sur {}", targetDir);
    }
}
