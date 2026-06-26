package org.program.pair.domain.user.dto;

import java.time.Instant;
import java.util.UUID;

public record UserActivityDto(
    UUID id,
    String activityName,
    Boolean visibleOnMap,
    String customDescription,
    String level,
    String format,
    Instant createdAt
) {}
