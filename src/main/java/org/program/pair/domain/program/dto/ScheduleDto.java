package org.program.pair.domain.program.dto;

import java.time.Instant;
import java.util.UUID;

public record ScheduleDto(
    UUID id,
    String placeName,
    String placeType,
    Double lat,
    Double lng,
    String displayAddress,
    Instant startsAt,
    Instant endsAt,
    String recurrenceRule,
    Integer maxParticipants
) {}
