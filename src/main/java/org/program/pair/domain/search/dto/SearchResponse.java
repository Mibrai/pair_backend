package org.program.pair.domain.search.dto;

import java.util.List;

public record SearchResponse(
    String type,                         // "results" | "clarification" | "empty"
    List<SearchResultDto> results,
    String clarificationQuestion,        // si type == "clarification"
    List<String> suggestedAlternatives,  // si type == "empty"
    SearchIntent parsedIntent            // pour debug / affichage client
) {
    public static SearchResponse clarification(String question, SearchIntent intent) {
        return new SearchResponse("clarification", List.of(), question, List.of(), intent);
    }

    public static SearchResponse empty(List<String> alternatives, SearchIntent intent) {
        return new SearchResponse("empty", List.of(), null, alternatives, intent);
    }

    public static SearchResponse results(List<SearchResultDto> results, SearchIntent intent) {
        return new SearchResponse("results", results, null, List.of(), intent);
    }
}
