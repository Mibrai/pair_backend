package org.program.pair.domain.map.dto;

import java.time.Instant;
import java.util.UUID;

public record MapActivityMarkerDto(
    UUID activityId,
    String activityName,
    String activitySlug,
    String categoryName,
    String categoryIcon,
    String categoryColorRamp,
    double lat,
    double lng,
    Double distanceKm,
    int programCount,
    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    Instant nextSessionAt,
    String address
) {}
