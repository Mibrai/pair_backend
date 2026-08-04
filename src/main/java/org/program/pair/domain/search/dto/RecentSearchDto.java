package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record RecentSearchDto(
    @Schema(description = "Identifiant stable de l'entrée d'historique, utilisable sur "
        + "DELETE /api/search/recent/{id}. Stable entre deux appels tant que l'entrée "
        + "n'est pas supprimée. Chaque recherche crée une entrée distincte : deux "
        + "recherches identiques donnent deux entrées, donc deux id.")
    UUID id,
    String query,
    Instant searchedAt
) {}
