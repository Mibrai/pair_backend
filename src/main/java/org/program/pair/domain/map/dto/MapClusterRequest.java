package org.program.pair.domain.map.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MapClusterRequest(
    @NotNull Double north,
    @NotNull Double south,
    @NotNull Double east,
    @NotNull Double west,
    @NotNull @Min(1) @Max(20) Integer zoom,
    UUID activityId,
    String level,
    String format
) {}
