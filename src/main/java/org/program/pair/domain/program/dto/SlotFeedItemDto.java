package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.UUID;

public record SlotFeedItemDto(
    UUID scheduleId,
    UUID programId,
    String programTitle,

    @Schema(description = "Activité pratiquée. Même identifiant que le filtre activityId "
        + "de GET /slots/feed.")
    UUID activityId,
    String activityName,

    @Schema(description = "Catégorie de l'activité. Un identifiant, là où categoryColorRamp "
        + "n'est qu'une intention de teinte : c'est celui-ci qui permet de filtrer, de "
        + "recouper avec /categories, ou de rattraper un filtre côté client.")
    UUID categoryId,
    String categoryColorRamp,

    String level,
    String format,
    UserPublicDto host,
    String placeName,
    String displayAddress,   // null si lieu privé non partagé
    Double lat,              // null si lieu privé non partagé
    Double lng,
    Double distanceMeters,   // null hors contexte de feed géolocalisé
    Instant startsAt,
    Instant endsAt,

    @Schema(description = "Règle de récurrence RFC 5545, sans le préfixe RRULE:, au même "
        + "format que ScheduleDto.recurrenceRule. Nulle pour une séance unique. "
        + "startsAt/endsAt ne décrivent que la *prochaine* occurrence : sans cette règle, "
        + "un engagement hebdomadaire est indiscernable d'une séance unique, et un conflit "
        + "d'agenda à cinq semaines passe inaperçu.")
    String recurrenceRule,

    @Schema(description = "Durée d'une séance en minutes. Vaut endsAt - startsAt quand les "
        + "deux sont connus, sinon la durée déclarée sur le programme. Nulle quand ni l'une "
        + "ni l'autre n'existe : l'API ne devine pas de durée, à charge de l'appelant de "
        + "décider ce qu'il fait de l'inconnu.")
    Integer sessionDurationMinutes,

    @Schema(description = "Instant de publication du créneau, en UTC. C'est la date sur "
        + "laquelle porte le filtre createdSince — celle qui répond à « qu'y a-t-il de "
        + "neuf ? », que startsAt ne sait pas exprimer.")
    Instant createdAt,

    Integer maxParticipants,
    Integer participantCount,
    Boolean isOpenToPartners,
    String welcomeNote,
    String myParticipationStatus, // null si je n'ai pas rejoint

    @Schema(description = "Mon rang dans la liste d'attente, à partir de 1. Nul si je "
        + "n'y suis pas. C'est le serveur qui le tient : le déduire côté client "
        + "supposerait de connaître toute la file, qui n'est visible que de l'hôte.")
    Integer myWaitlistPosition
) {}
