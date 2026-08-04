package org.program.pair.domain.search;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.search.dto.PopularSearchDto;
import org.program.pair.domain.search.dto.RecentSearchDto;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.dto.TagDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SemanticSearchService searchService;
    private final SearchHistoryService searchHistoryService;
    private final SearchService tagSearchService;

    /**
     * POST /api/search
     *
     * Recherche intelligente en langage naturel
     *
     * Exemple de requête:
     * {
     *   "query": "je cherche quelqu'un pour faire du yoga le matin",
     *   "lat": 48.8566,
     *   "lng": 2.3522,
     *   "radiusMeters": 5000
     * }
     *
     * Réponses possibles:
     * - type: "results" - Liste de programmes/utilisateurs trouvés
     * - type: "clarification" - Demande de précision (requête trop vague)
     * - type: "empty" - Aucun résultat, suggestions alternatives
     */
    @PostMapping
    public SearchResponse search(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SearchRequest request) {
        return searchService.search(request, principal.getId());
    }

    /**
     * GET /api/search/popular?limit={limit}
     *
     * Get popular searches across all users (last 30 days)
     *
     * @param limit Maximum number of results (default: 10, max: 50)
     * @return List of popular searches with their counts
     */
    @GetMapping("/popular")
    public List<PopularSearchDto> getPopularSearches(
            @RequestParam(defaultValue = "10") int limit) {
        if (limit > 50) {
            limit = 50;
        }
        return searchHistoryService.getPopularSearches(limit);
    }

    /**
     * GET /api/search/recent
     *
     * Get user's recent searches (last 10)
     *
     * @return List of recent searches
     */
    @GetMapping("/recent")
    public List<RecentSearchDto> getRecentSearches(
            @AuthenticationPrincipal UserPrincipal principal) {
        return searchHistoryService.getRecentSearches(principal.getId());
    }

    /**
     * DELETE /api/search/recent
     *
     * Clear user's recent searches
     *
     * @return 204 No Content
     */
    @DeleteMapping("/recent")
    public ResponseEntity<Void> clearRecentSearches(
            @AuthenticationPrincipal UserPrincipal principal) {
        searchHistoryService.clearRecentSearches(principal.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/search/recent/{id}
     *
     * Supprime une seule entrée d'historique, identifiée par l'{@code id} rendu
     * par GET /api/search/recent.
     *
     * @return 204 No Content, ou 404 SEARCH_HISTORY_ENTRY_NOT_FOUND si l'entrée
     *         n'existe pas ou appartient à un autre utilisateur (indistinguables
     *         à dessein). Non idempotent : un second appel renvoie 404.
     */
    @DeleteMapping("/recent/{id}")
    public ResponseEntity<Void> deleteRecentSearch(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        searchHistoryService.deleteRecentSearch(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/search/tags?q={query}&limit={limit}
     *
     * Search tags matching the query.
     * For MVP: returns categories that match the query.
     *
     * @param query Search query for tag names (required)
     * @param limit Maximum number of results (default: 20, max: 50)
     * @return List of tags with their usage counts
     */
    @GetMapping("/tags")
    public List<TagDto> searchTags(
            @RequestParam(name = "q") String query,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit > 50) {
            limit = 50;
        }
        return tagSearchService.searchTags(query, limit);
    }

    /**
     * GET /api/search/tags/popular?limit={limit}
     *
     * Get popular tags sorted by usage count.
     * For MVP: returns categories sorted by number of activities.
     *
     * @param limit Maximum number of results (default: 20, max: 50)
     * @return List of popular tags with their usage counts
     */
    @GetMapping("/tags/popular")
    public List<TagDto> getPopularTags(
            @RequestParam(defaultValue = "20") int limit) {
        if (limit > 50) {
            limit = 50;
        }
        return tagSearchService.getPopularTags(limit);
    }
}
