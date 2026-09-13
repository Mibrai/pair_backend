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

    @io.swagger.v3.oas.annotations.media.Schema(description = "Fin effective de la séance : "
        + "endsAt quand il est déclaré, sinon startsAt + 2 h (P-BA-19). C'est la frontière à "
        + "utiliser pour « terminé ». Toujours renseignée.")
    Instant effectiveEndsAt,

    @io.swagger.v3.oas.annotations.media.Schema(description = "Vrai quand endsAt a été déclaré. "
        + "Faux seulement pour d'anciens créneaux : toute écriture exige désormais une fin.")
    boolean endsAtDeclared,
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
