package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Renommé de {@code VisibilityRequest} (P-BA-17), homonyme de celui des cartes-souvenirs.
 *
 * <p>Sans {@code @NotNull}, et c'est voulu (P-BS-15) : {@code visible} absent fait
 * basculer la visibilité, un contrat que {@code ActivityService.toggleMapVisibility}
 * tient depuis l'origine.
 */
public record ActivityVisibilityRequest(
    @Schema(description = "Visible sur la carte. Absent ou null : la visibilité bascule.")
    Boolean visible
) {}
