package org.program.pair.domain.search.dto;

import java.util.List;

public record SearchResponse(
    String type,                         // "results" | "clarification" | "empty"
    List<SearchResultDto> results,
    String clarificationQuestion,        // si type == "clarification"
    List<String> suggestedAlternatives,  // si type == "empty" — conservé pour compat clients existants
    SearchIntent parsedIntent,           // pour debug / affichage client
    List<EmptyStateActionDto> emptyStateActions // si type == "empty" — actions structurées exploitables par le client
) {
    public static SearchResponse clarification(String question, SearchIntent intent) {
        return new SearchResponse("clarification", List.of(), question, List.of(), intent, List.of());
    }

    public static SearchResponse empty(List<EmptyStateActionDto> actions, SearchIntent intent) {
        List<String> labels = actions.stream().map(EmptyStateActionDto::label).toList();
        return new SearchResponse("empty", List.of(), null, labels, intent, actions);
    }

    public static SearchResponse results(List<SearchResultDto> results, SearchIntent intent) {
        return new SearchResponse("results", results, null, List.of(), intent, List.of());
    }
}
