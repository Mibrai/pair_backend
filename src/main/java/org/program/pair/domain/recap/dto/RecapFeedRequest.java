package org.program.pair.domain.recap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Paramètres du fil de cartes publiques.
 *
 * <p>Mêmes noms et mêmes bornes que {@code SlotFeedRequest} : le client
 * réutilise la position et le rayon qu'il partage déjà entre ses écrans, sans
 * réglage supplémentaire ni conversion.
 */
public record RecapFeedRequest(

    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,

    @Schema(description = "Rayon en mètres, entre 500 et 50000 — les bornes du feed de créneaux.")
    @NotNull @Min(500) @Max(50000) Integer radiusMeters
) {}
