package org.program.pair.domain.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.search.dto.*;
import org.program.pair.repository.ProgramRepository;
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
    private final EmbeddingService embeddingService;
    private final FullTextSearchService fullTextSearchService;
    private final ProgramRepository programRepository;
    private final SearchLogRepository searchLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SearchResponse search(SearchRequest request, UUID userId) {
        log.info("Search request from user {}: '{}'", userId, request.query());

        // 1. Logger la recherche brute
        String method = embeddingService.isConfigured() ? "semantic" : "fulltext";
        SearchLog searchLog = SearchLog.builder()
            .user(userRepository.getReferenceById(userId))
            .rawQuery(request.query())
            .searchMethod(method)
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
        SearchRequest searchRequest = new SearchRequest(
            request.query(), request.lat(), request.lng(), radius);

        List<SearchResultDto> results;

        if (embeddingService.isConfigured()) {
            // Recherche sémantique via pgvector
            float[] embedding = embeddingService.generateEmbedding(request.query());
            if (embedding != null) {
                results = toSearchResultDtos(
                    programRepository.semanticSearchInRadius(
                        embeddingService.toVectorString(embedding),
                        request.lat(), request.lng(), radius, 20),
                    request.lat(), request.lng());
            } else {
                results = fulltextFallback(intent, searchRequest);
            }
        } else {
            results = fulltextFallback(intent, searchRequest);
        }

        // Filtrer par niveau si spécifié par le LLM
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

    private List<SearchResultDto> fulltextFallback(SearchIntent intent, SearchRequest searchRequest) {
        String keywords = intent.activityKeyword() != null ? intent.activityKeyword() : searchRequest.query();
        List<SearchResultDto> results = fullTextSearchService.searchPrograms(keywords, searchRequest, 20);

        if (results.isEmpty() && intent.activityKeyword() != null) {
            log.debug("Full-text returned nothing, trying exact activity match");
            results = fullTextSearchService.searchByActivity(intent.activityKeyword(), searchRequest, 20);
        }
        return results;
    }

    private List<SearchResultDto> toSearchResultDtos(
            List<org.program.pair.domain.program.Program> programs,
            double lat, double lng) {

        return programs.stream().map(p -> {
            var owner = p.getUserActivity().getUser();
            var ownerLoc = owner.getLocation();
            double dist = 0;
            if (ownerLoc != null) {
                double dLat = Math.toRadians(ownerLoc.getY() - lat);
                double dLng = Math.toRadians(ownerLoc.getX() - lng);
                double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                    + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(ownerLoc.getY()))
                    * Math.sin(dLng/2) * Math.sin(dLng/2);
                dist = 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            }
            boolean isOnline = owner.getLastActiveAt() != null
                && owner.getLastActiveAt().isAfter(java.time.Instant.now().minusSeconds(300));

            return new SearchResultDto(
                "program", p.getId(), p.getTitle(),
                p.getDescription() != null
                    ? p.getDescription().substring(0, Math.min(200, p.getDescription().length()))
                    : null,
                owner.getAvatarUrl(),
                ownerLoc != null ? ownerLoc.getY() : null,
                ownerLoc != null ? ownerLoc.getX() : null,
                dist, 0f,
                p.getUserActivity().getActivity().getName(),
                p.getUserActivity().getLevel() != null ? p.getUserActivity().getLevel().name() : null,
                p.getUserActivity().getFormat() != null ? p.getUserActivity().getFormat().name() : null,
                isOnline,
                owner.getVerificationStatus().name()
            );
        }).toList();
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
