package org.program.pair.domain.map.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record MapBoundsRequest(
    @NotNull Double north,
    @NotNull Double south,
    @NotNull Double east,
    @NotNull Double west,
    List<UUID> categoryIds,
    List<String> activityLevels,
    List<String> formats,
    Integer limit,
    Integer offset
) {
    public MapBoundsRequest {
        if (limit == null) limit = 100;
        if (offset == null) offset = 0;
    }
}
