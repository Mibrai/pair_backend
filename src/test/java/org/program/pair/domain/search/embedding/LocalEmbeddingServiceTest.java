package org.program.pair.domain.search.embedding;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Charge le vrai modèle ONNX (téléchargement depuis la GitHub Release si absent
 * du cache local) — coût réseau/CPU réel, volontairement exclu de `mvn test`
 * par défaut (voir `excludedGroups` dans le plugin surefire de pom.xml).
 * Exécution explicite : {@code mvn test -DexcludedGroups=}.
 */
@Tag("model-integration")
class LocalEmbeddingServiceTest {

    private static LocalEmbeddingService service;

    @BeforeAll
    static void loadModel() {
        service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "modelPath", System.getProperty("user.home") + "/.cache/meetdo-embeddings");
        ReflectionTestUtils.setField(service, "modelBaseUrl",
            "https://github.com/Mibrai/pair_backend/releases/download/models-v1");
        service.init();
    }

    @AfterAll
    static void cleanup() {
        service.cleanup();
    }

    @Test
    void embedding_estDeDimension384() {
        assertThat(service.generateEmbedding("yoga")).hasSize(384);
    }

    @Test
    void embedding_estNormaliseL2() {
        float[] embedding = service.generateEmbedding("un texte quelconque pour tester la norme");
        assertThat(norm(embedding)).isCloseTo(1.0, within(0.01));
    }

    @Test
    void embedding_nEstJamaisLeVecteurNulPourUnTexteReel() {
        assertThat(LocalEmbeddingService.isZeroVector(service.generateEmbedding("yoga"))).isFalse();
    }

    @Test
    void yoga_frEnDe_produisentDesEmbeddingsMutuellementProches() {
        float[] fr = service.generateEmbedding("yoga");
        float[] en = service.generateEmbedding("yoga");
        float[] de = service.generateEmbedding("Yoga");

        assertThat(cosine(fr, en)).isGreaterThan(0.85);
        assertThat(cosine(fr, de)).isGreaterThan(0.85);
    }

    @Test
    void runningEtLaufen_sontSemantiquementProches() {
        float[] running = service.generateEmbedding("running");
        float[] laufen = service.generateEmbedding("Laufen");

        assertThat(cosine(running, laufen)).isGreaterThan(0.85);
    }

    private static double norm(float[] v) {
        double sumSquares = 0;
        for (float x : v) sumSquares += (double) x * x;
        return Math.sqrt(sumSquares);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
