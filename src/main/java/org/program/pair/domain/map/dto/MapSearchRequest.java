package org.program.pair.domain.map.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MapSearchRequest(
    @NotNull Double lat,
    @NotNull Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,
    UUID activityId,
    String level,
    String format
) {}
