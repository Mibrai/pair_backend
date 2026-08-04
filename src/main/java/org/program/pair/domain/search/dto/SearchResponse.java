package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public record SearchResponse(
    String type,                         // "results" | "clarification" | "empty"
    List<SearchResultDto> results,
    String clarificationQuestion,        // si type == "clarification"
    List<String> suggestedAlternatives,  // si type == "empty" — conservé pour compat clients existants
    SearchIntent parsedIntent,           // pour debug / affichage client
    List<EmptyStateActionDto> emptyStateActions, // si type == "empty" — actions structurées exploitables par le client

    @Schema(description = "Total des résultats de la requête, toutes catégories, dans le "
        + "rayon demandé — indépendant de pageSize. Plafonné par la limite de candidats "
        + "du moteur : au-delà, c'est un « au moins N ». Nul sur clarification et empty.")
    Integer totalCount,

    @Schema(description = "Page servie, indexée à 0. Nul sur clarification et empty.")
    Integer page,

    @Schema(description = "Taille de page effective. Nul sur clarification et empty.")
    Integer pageSize,

    @Schema(description = "Vrai s'il reste au moins un résultat après celle-ci. Faux sur "
        + "la dernière page, y compris quand totalCount est un multiple exact de pageSize.")
    Boolean hasMore,

    @Schema(description = "Total par type sur toute la requête, pas sur la page courante — "
        + "sinon un onglet « Personnes (3) » afficherait 3 puis 0. Clés : user, program, "
        + "slot. La somme vaut totalCount.")
    Map<String, Integer> countsByType
) {
    /**
     * Les réponses sans résultat ne sont pas paginées : les champs de pagination
     * y sont nuls plutôt qu'à zéro, pour que le client distingue « pas de
     * pagination ici » de « zéro résultat sur cette page ».
     */
    public static SearchResponse clarification(String question, SearchIntent intent) {
        return new SearchResponse("clarification", List.of(), question, List.of(), intent, List.of(),
            null, null, null, null, null);
    }

    public static SearchResponse empty(List<EmptyStateActionDto> actions, SearchIntent intent) {
        List<String> labels = actions.stream().map(EmptyStateActionDto::label).toList();
        return new SearchResponse("empty", List.of(), null, labels, intent, actions,
            null, null, null, null, null);
    }

    public static SearchResponse results(List<SearchResultDto> pageResults, SearchIntent intent,
                                          int totalCount, int page, int pageSize,
                                          Map<String, Integer> countsByType) {
        boolean hasMore = (long) page * pageSize + pageResults.size() < totalCount;
        return new SearchResponse("results", pageResults, null, List.of(), intent, List.of(),
            totalCount, page, pageSize, hasMore, countsByType);
    }
}
