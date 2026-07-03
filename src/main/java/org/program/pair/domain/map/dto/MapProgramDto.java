package org.program.pair.domain.map.dto;

import java.time.Instant;
import java.util.UUID;

public record MapProgramDto(
    UUID id,
    String title,
    String description,
    String activityName,
    String categoryColorRamp,
    String placeName,
    String addressPublic,
    double lat,
    double lng,
    Instant startsAt,
    Instant endsAt,
    Integer maxParticipants,
    String organizerName,
    UUID organizerId
) {}
