package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public record SlotFeedRequest(
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,
    UUID activityId,        // filtre optionnel
    UUID categoryId,        // filtre optionnel
    Instant from,           // défaut : maintenant
    Instant to              // défaut : maintenant + 7 jours
) {}
