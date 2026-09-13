package org.program.pair.domain.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sans {@code @NotNull}, et c'est voulu (P-BS-15) : la mise à jour est partielle,
 * {@code isActive} absent laisse l'alerte telle quelle.
 */
public record UpdateActivityAlertRequest(
    @Schema(description = "Alerte active. Absent ou null : inchangé.")
    Boolean isActive
) {}
