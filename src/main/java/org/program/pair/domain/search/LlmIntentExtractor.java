package org.program.pair.domain.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.search.dto.SearchIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmIntentExtractor {

    @Value("${llm.api-url:https://api.anthropic.com/v1/messages}")
    private String llmApiUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:claude-sonnet-4-6}")
    private String model;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ActivityTaxonomy activityTaxonomy;

    public SearchIntent extractIntent(String userQuery) {
        // Si pas d'API key configurée, fallback direct
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("${ANTHROPIC_API_KEY}")) {
            log.warn("LLM API key non configurée, utilisation du fallback");
            return createFallbackIntent(userQuery);
        }

        String systemPrompt = """
            Tu es un assistant d'extraction d'intention pour un réseau social d'activités sportives et culturelles,
            utilisé par des utilisateurs en français, anglais ou allemand.
            Analyse la phrase de l'utilisateur et retourne UNIQUEMENT un JSON valide,
            sans texte avant ou après, avec exactement ces champs :
            {
              "activityKeyword": "string ou null (dans la langue de l'utilisateur)",
              "categoryHint": "string ou null",
              "level": "BEGINNER|INTERMEDIATE|ADVANCED|EXPERT ou null",
              "format": "SOLO|GROUP|BOTH ou null",
              "suggestedRadius": nombre en mètres (défaut 5000),
              "timeHint": "string ou null",
              "needsClarification": boolean,
              "clarificationQuestion": "string ou null",
              "canonicalActivitySlug": "string ou null"
            }

            "canonicalActivitySlug" DOIT être choisi UNIQUEMENT parmi cette liste connue
            (ou null si aucune ne correspond clairement à la requête, quelle que soit sa langue) :
            %s

            Exemples :
            - "je veux faire du sport" → needsClarification: true,
              clarificationQuestion: "Quel type de sport vous intéresse ?"
            - "cherche partenaire escalade débutant" → activityKeyword: "escalade",
              level: "BEGINNER", canonicalActivitySlug: "climbing", needsClarification: false
            - "quelqu'un pour courir dimanche matin près de chez moi" → activityKeyword: "course",
              timeHint: "dimanche matin", format: "GROUP", canonicalActivitySlug: "running", needsClarification: false
            - "Laufen am Sonntag" → activityKeyword: "Laufen", timeHint: "Sonntag",
              canonicalActivitySlug: "running", needsClarification: false
            - "tennis niveau intermédiaire" → activityKeyword: "tennis", level: "INTERMEDIATE", canonicalActivitySlug: "tennis"
            - "yoga en groupe" → activityKeyword: "yoga", format: "GROUP", canonicalActivitySlug: "yoga"
            """.formatted(String.join(", ", activityTaxonomy.knownSlugs()));

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "max_tokens", 300,
            "system", systemPrompt,
            "messages", List.of(Map.of("role", "user", "content", userQuery))
        );

        try {
            WebClient webClient = webClientBuilder
                .baseUrl(llmApiUrl)
                .build();

            String response = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("LLM API call failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();

            if (response == null || response.isEmpty()) {
                return createFallbackIntent(userQuery);
            }

            // Extraire le contenu texte de la réponse Anthropic
            JsonNode root = objectMapper.readTree(response);
            String json = root.path("content").get(0).path("text").asText();

            return objectMapper.readValue(json, SearchIntent.class);

        } catch (Exception e) {
            log.warn("LLM intent extraction échoué, fallback sur recherche textuelle : {}", e.getMessage());
            return createFallbackIntent(userQuery);
        }
    }

    private SearchIntent createFallbackIntent(String userQuery) {
        // Fallback simple : utiliser la phrase brute comme mot-clé
        // Détection basique de certains mots-clés
        String lowercaseQuery = userQuery.toLowerCase();

        String level = null;
        if (lowercaseQuery.contains("débutant") || lowercaseQuery.contains("beginner")) {
            level = "BEGINNER";
        } else if (lowercaseQuery.contains("intermédiaire") || lowercaseQuery.contains("intermediate")) {
            level = "INTERMEDIATE";
        } else if (lowercaseQuery.contains("avancé") || lowercaseQuery.contains("advanced")) {
            level = "ADVANCED";
        } else if (lowercaseQuery.contains("expert")) {
            level = "EXPERT";
        }

        String format = null;
        if (lowercaseQuery.contains("groupe") || lowercaseQuery.contains("group")) {
            format = "GROUP";
        } else if (lowercaseQuery.contains("solo") || lowercaseQuery.contains("seul")) {
            format = "SOLO";
        }

        // La taxonomie canonique EN/DE/FR permet de reconnaître une activité connue
        // même sans LLM disponible (ex: "Laufen" -> "running").
        String canonicalSlug = activityTaxonomy.matchSlugs(userQuery).stream().findFirst().orElse(null);

        // Si la requête est très courte et vague, et qu'aucune activité connue n'est reconnue
        boolean needsClarification = userQuery.trim().split("\\s+").length <= 2 && canonicalSlug == null
            && !lowercaseQuery.matches(".*(tennis|football|yoga|course|escalade|basket|natation).*");

        String clarification = needsClarification
            ? "Pouvez-vous préciser quelle activité vous recherchez ?"
            : null;

        return new SearchIntent(
            userQuery,
            null,
            level,
            format,
            5000,
            null,
            needsClarification,
            clarification,
            canonicalSlug
        );
    }
}
