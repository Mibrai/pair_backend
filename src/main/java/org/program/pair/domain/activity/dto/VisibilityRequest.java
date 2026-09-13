package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sans {@code @NotNull}, et c'est voulu (P-BS-15) : {@code visible} absent fait
 * basculer la visibilité, un contrat que {@code ActivityService.toggleMapVisibility}
 * tient depuis l'origine.
 */
public record VisibilityRequest(
    @Schema(description = "Visible sur la carte. Absent ou null : la visibilité bascule.")
    Boolean visible
) {}
