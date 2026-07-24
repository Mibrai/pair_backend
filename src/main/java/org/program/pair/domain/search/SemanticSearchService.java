package org.program.pair.domain.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.search.dto.*;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.SearchLogRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    private final LlmIntentExtractor intentExtractor;
    private final EmbeddingService embeddingService;
    private final FullTextSearchService fullTextSearchService;
    private final ProgramRepository programRepository;
    private final ActivityRepository activityRepository;
    private final SearchLogRepository searchLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ActivityTaxonomy activityTaxonomy;

    /** Similarité cosine minimale (0-1) pour qu'un résultat vectoriel soit retenu. */
    @Value("${search.embedding.min-similarity:0.25}")
    private double minSimilarity;

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
            List<EmptyStateActionDto> actions = buildEmptyStateActions(request, intent, radius);
            return SearchResponse.empty(actions, intent);
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

        // 1. Couche déterministe : taxonomie d'activités canonique EN/DE/FR.
        // Garantit le matching cross-lingue sur les activités connues (ex: "Laufen"
        // -> slug "running" -> "course à pied"/"marche à pied"/"running"),
        // indépendamment de la qualité du rappel sémantique.
        Set<String> taxonomyLabels = activityTaxonomy.matchLabels(
            request.query(), intent.activityKeyword(), intent.canonicalActivitySlug());
        List<SearchResultDto> taxonomyResults = taxonomyLabels.isEmpty()
            ? List.of()
            : fullTextSearchService.searchByTaxonomyLabels(taxonomyLabels, searchRequest, 20);

        // 2. Couche de rappel : embeddings multilingues (ou full-text en fallback).
        List<SearchResultDto> recallResults;
        if (embeddingService.isConfigured()) {
            float[] embedding = embeddingService.generateEmbedding(request.query());
            if (embedding != null) {
                double maxDistance = 1 - minSimilarity;
                recallResults = toSearchResultDtos(
                    programRepository.semanticSearchInRadius(
                        embeddingService.toVectorString(embedding),
                        request.lat(), request.lng(), radius, maxDistance, 20),
                    request.lat(), request.lng());
            } else {
                recallResults = fulltextFallback(intent, searchRequest);
            }
        } else {
            recallResults = fulltextFallback(intent, searchRequest);
        }

        // 3. Fusion : les matchs taxonomiques (précision) priment, complétés par le
        // rappel sémantique/full-text, dédupliqués par programme.
        List<SearchResultDto> results = mergeResults(taxonomyResults, recallResults, 20);

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

    private List<SearchResultDto> mergeResults(
            List<SearchResultDto> primary, List<SearchResultDto> secondary, int limit) {
        LinkedHashMap<UUID, SearchResultDto> merged = new LinkedHashMap<>();
        primary.forEach(r -> merged.put(r.id(), r));
        secondary.forEach(r -> merged.putIfAbsent(r.id(), r));
        return merged.values().stream().limit(limit).toList();
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

    // Package-private (au lieu de private) pour permettre un test unitaire ciblé
    // de la priorité imageUrl / media[0] sans dépendances Spring/DB.
    List<SearchResultDto> toSearchResultDtos(
            List<org.program.pair.domain.program.Program> programs,
            double lat, double lng) {

        return programs.stream().map(p -> {
            var ua    = p.getUserActivity();
            var owner = ua.getUser();
            var act   = ua.getActivity();
            var cat   = act.getCategory();
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

            // Image de couverture dédiée en priorité (cohérent avec la page détail),
            // repli sur le premier média IMAGE de la galerie si absente.
            String thumbnailUrl = p.getImageUrl() != null
                ? p.getImageUrl()
                : p.getMedia().stream()
                    .filter(m -> m.getMediaType() == org.program.pair.domain.program.MediaType.IMAGE)
                    .min(java.util.Comparator.comparingInt(
                        org.program.pair.domain.program.ProgramMedia::getSortOrder))
                    .map(org.program.pair.domain.program.ProgramMedia::getUrl)
                    .orElse(null);

            return new SearchResultDto(
                "program",
                p.getId(),
                p.getTitle(),
                p.getDescription() != null
                    ? p.getDescription().substring(0, Math.min(200, p.getDescription().length()))
                    : null,
                owner.getAvatarUrl(),
                ownerLoc != null ? ownerLoc.getY() : null,
                ownerLoc != null ? ownerLoc.getX() : null,
                dist, 0f,
                act.getName(),
                ua.getLevel() != null ? ua.getLevel().name() : null,
                ua.getFormat() != null ? ua.getFormat().name() : null,
                isOnline,
                owner.getVerificationStatus().name(),
                // champs enrichis
                ua.getId(),
                cat != null ? cat.getId() : null,
                cat != null ? cat.getName() : null,
                owner.getId(),
                owner.getDisplayName(),
                owner.getAvatarUrl(),
                thumbnailUrl,
                null,   // averageScore : non chargé en JPA (évite N+1)
                null,   // reviewCount
                null,   // enrolledCount
                p.getStatus().name(),
                p.getLocationType() != null ? p.getLocationType().name() : null,
                null,   // city
                p.getCreatedAt(),
                p.getUpdatedAt()
            );
        }).toList();
    }

    private List<EmptyStateActionDto> buildEmptyStateActions(SearchRequest request, SearchIntent intent, int radius) {
        List<EmptyStateActionDto> actions = new java.util.ArrayList<>();

        // 1. Élargir le rayon (libellé conservé à l'identique : des clients/tests
        // existants font correspondre ce texte, seul le type devient structuré)
        int expanded = Math.min(radius * 3, 50000);
        if (expanded > radius) {
            actions.add(new EmptyStateActionDto("EXPAND_RADIUS",
                "Élargir la zone de recherche à " + (expanded / 1000) + " km",
                Map.of("radiusMeters", expanded)));
        }

        // Résout un identifiant d'activité concret à partir du slug canonique
        // détecté par le LLM, pour rendre CREATE_SLOT/SET_ALERT actionnables.
        UUID activityId = intent.canonicalActivitySlug() != null
            ? activityRepository.findBySlug(intent.canonicalActivitySlug()).map(Activity::getId).orElse(null)
            : null;

        if (activityId != null) {
            // 2. Créer soi-même un créneau (transformer le vide en action)
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                "Proposer un créneau et être le premier ici",
                Map.of("activityId", activityId)));

            // 3. Poser une alerte
            actions.add(new EmptyStateActionDto("SET_ALERT",
                "Me prévenir quand quelqu'un arrive",
                Map.of("activityId", activityId,
                       "lat", request.lat(), "lng", request.lng(),
                       "radiusMeters", radius)));

            // 4. Activités de la même catégorie ayant du monde à proximité
            List<Activity> neighbours = activityRepository
                .findSimilarActivitiesWithNearbyUsers(activityId, request.lat(), request.lng(), radius, 3);
            for (Activity a : neighbours) {
                actions.add(new EmptyStateActionDto("SIMILAR_ACTIVITY",
                    "Voir " + a.getName() + " à la place",
                    Map.of("activityId", a.getId().toString(), "name", a.getName())));
            }
        } else if (intent.activityKeyword() != null) {
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                "Être le premier à proposer " + intent.activityKeyword() + " dans votre zone",
                Map.of()));
        } else {
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                "Créer votre propre programme d'activité",
                Map.of()));
        }

        return actions;
    }
}
