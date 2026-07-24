package org.program.pair.domain.alert.dto;

import java.time.Instant;
import java.util.UUID;

public record ActivityAlertDto(
    UUID id,
    UUID activityId,
    String activityName,
    Double lat,
    Double lng,
    Integer radiusMeters,
    Boolean isActive,
    Instant lastTriggeredAt,
    Instant createdAt
) {}
