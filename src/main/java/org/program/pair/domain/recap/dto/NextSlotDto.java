package org.program.pair.domain.recap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * La prochaine occasion de vivre le même moment.
 *
 * <p>Ni adresse ni coordonnées : une carte-souvenir se lit sans y avoir été, et
 * le lieu exact d'un créneau privé n'a rien à faire là. Le nom du lieu suffit à
 * décider ; le reste s'obtient en rejoignant le créneau.
 */
public record NextSlotDto(

    UUID scheduleId,
    Instant startsAt,
    String placeName,
    int participantCount,

    @Schema(description = "Plafond de participants, nul quand le créneau n'en déclare pas.")
    Integer maxParticipants,

    @Schema(description = "Ai-je déjà ma place sur ce créneau — en l'ayant rejoint, en "
        + "suivant le programme, ou en l'hébergeant ?")
    boolean alreadyJoined
) {}
