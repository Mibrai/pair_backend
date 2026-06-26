package org.program.pair.domain.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;

import java.util.UUID;

public record UpsertUserActivityRequest(
    @NotNull UUID activityId,
    Boolean visibleOnMap,
    @Size(max = 500) String customDescription,
    ActivityLevel level,
    ActivityFormat format
) {}
