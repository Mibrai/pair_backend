package org.program.pair.domain.alert.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateActivityAlertRequest(
    @NotNull UUID activityId,
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
    @Min(500) @Max(50000) Integer radiusMeters
) {}
