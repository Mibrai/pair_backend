package org.program.pair.domain.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    @Value("${embedding.api-url:https://api.openai.com/v1/embeddings}")
    private String embeddingApiUrl;

    @Value("${embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${embedding.model:text-embedding-3-small}")
    private String model;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return embeddingApiKey != null && !embeddingApiKey.isBlank();
    }

    public float[] generateEmbedding(String text) {
        if (!isConfigured()) {
            log.debug("Embedding API not configured, skipping vector generation");
            return null;
        }
        String truncated = text.length() > 8000 ? text.substring(0, 8000) : text;

        Map<String, Object> body = Map.of("model", model, "input", truncated);

        try {
            String response = webClientBuilder.build()
                .post()
                .uri(embeddingApiUrl)
                .header("Authorization", "Bearer " + embeddingApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return null;

            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");

            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;

        } catch (Exception e) {
            log.warn("Embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
