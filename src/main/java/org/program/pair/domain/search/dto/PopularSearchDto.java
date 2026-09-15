package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un terme de recherche populaire. Rien d'autre : plus de {@code searchCount}
 * depuis le 15/09 (demande mobile recherche) — jamais un compte là où il n'est
 * pas nécessaire.
 */
public record PopularSearchDto(
    @Schema(description = "Un nom d'activité ou de catégorie du catalogue, cherché par au moins "
        + "cinq personnes distinctes sur 30 jours. Jamais une saisie libre.")
    String query
) {}
