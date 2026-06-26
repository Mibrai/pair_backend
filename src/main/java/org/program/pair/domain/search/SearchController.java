package org.program.pair.domain.search;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SemanticSearchService searchService;

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
}
