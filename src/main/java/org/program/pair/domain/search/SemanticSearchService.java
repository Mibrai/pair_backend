package org.program.pair.domain.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.search.dto.*;
import org.program.pair.repository.SearchLogRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    private final LlmIntentExtractor intentExtractor;
    private final FullTextSearchService fullTextSearchService;
    private final SearchLogRepository searchLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SearchResponse search(SearchRequest request, UUID userId) {
        log.info("Search request from user {}: '{}'", userId, request.query());

        // 1. Logger la recherche brute
        SearchLog searchLog = SearchLog.builder()
            .user(userRepository.getReferenceById(userId))
            .rawQuery(request.query())
            .searchMethod("fulltext")
            .build();

        // 2. Extraire l'intention via LLM (ou fallback)
        SearchIntent intent = intentExtractor.extractIntent(request.query());

        try {
            searchLog.setParsedIntent(objectMapper.writeValueAsString(intent));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize intent: {}", e.getMessage());
        }

        // 3. Si clarification nécessaire → répondre immédiatement
        if (intent.needsClarification()) {
            searchLog.setResultsCount(0);
            searchLogRepository.save(searchLog);
            log.info("Clarification needed for query: '{}'", request.query());
            return SearchResponse.clarification(intent.clarificationQuestion(), intent);
        }

        // 4. Déterminer le rayon de recherche
        int radius = determineRadius(request, intent);

        // 5. Recherche full-text dans la base
        List<SearchResultDto> results = performSearch(request, intent, radius);

        searchLog.setResultsCount(results.size());
        searchLogRepository.save(searchLog);

        // 6. Si aucun résultat → suggestions alternatives
        if (results.isEmpty()) {
            log.info("No results found for query: '{}'", request.query());
            List<String> alternatives = buildAlternativeSuggestions(request, intent, radius);
            return SearchResponse.empty(alternatives, intent);
        }

        // 7. Retourner les résultats
        log.info("Found {} results for query: '{}'", results.size(), request.query());
        return SearchResponse.results(results, intent);
    }

    private int determineRadius(SearchRequest request, SearchIntent intent) {
        if (request.radiusMeters() != null) {
            return request.radiusMeters();
        }
        if (intent.suggestedRadius() != null) {
            return intent.suggestedRadius();
        }
        return 5000; // Default 5km
    }

    private List<SearchResultDto> performSearch(SearchRequest request, SearchIntent intent, int radius) {
        // Créer une nouvelle requête avec le rayon déterminé
        SearchRequest searchRequest = new SearchRequest(
            request.query(),
            request.lat(),
            request.lng(),
            radius
        );

        // Essayer d'abord la recherche full-text avec tous les mots-clés
        String keywords = intent.activityKeyword() != null
            ? intent.activityKeyword()
            : request.query();

        List<SearchResultDto> results = fullTextSearchService.searchPrograms(
            keywords,
            searchRequest,
            20
        );

        // Si pas de résultats et qu'on a un mot-clé d'activité, essayer une recherche exacte
        if (results.isEmpty() && intent.activityKeyword() != null) {
            log.debug("Full-text search returned no results, trying exact activity search");
            results = fullTextSearchService.searchByActivity(
                intent.activityKeyword(),
                searchRequest,
                20
            );
        }

        // Filtrer par niveau si spécifié
        if (intent.level() != null && !results.isEmpty()) {
            results = results.stream()
                .filter(r -> r.level() != null && r.level().equalsIgnoreCase(intent.level()))
                .toList();
        }

        // Filtrer par format si spécifié
        if (intent.format() != null && !results.isEmpty()) {
            results = results.stream()
                .filter(r -> r.format() != null &&
                    (r.format().equalsIgnoreCase(intent.format()) || r.format().equalsIgnoreCase("BOTH")))
                .toList();
        }

        return results;
    }

    private List<String> buildAlternativeSuggestions(SearchRequest request, SearchIntent intent, int radius) {
        List<String> suggestions = new java.util.ArrayList<>();

        // Suggérer d'élargir la zone
        int newRadius = radius * 2;
        if (newRadius <= 50000) { // Max 50km
            suggestions.add("Élargir la zone de recherche à " + (newRadius / 1000) + " km");
        }

        // Suggérer de créer un programme
        if (intent.activityKeyword() != null) {
            suggestions.add("Être le premier à proposer " + intent.activityKeyword() + " dans votre zone");
        } else {
            suggestions.add("Créer votre propre programme d'activité");
        }

        // Suggérer une alerte
        suggestions.add("Recevoir une alerte quand quelqu'un propose cette activité");

        return suggestions;
    }
}
