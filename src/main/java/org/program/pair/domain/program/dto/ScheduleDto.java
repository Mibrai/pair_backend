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
    Integer maxParticipants,
    Boolean isOpenToPartners,
    String status,
    Integer participantCount,
    String welcomeNote,

    @io.swagger.v3.oas.annotations.media.Schema(description = "Niveau attendu déclaré "
        + "par l'organisateur pour cette séance, ou null quand il n'est pas précisé.")
    String level
) {}
