package org.program.pair.domain.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une séance terminée dont on n'a pas encore dit si on y était.
 *
 * <p><b>La liste est composée pour être consommée telle quelle.</b> Elle ne
 * contient que ce que {@code POST /api/attendances/{id}/confirm} acceptera :
 * les deux routes lisent les mêmes trois sources — hôte du créneau, participation
 * de créneau confirmée, inscription de programme active rattachée à ce créneau.
 * Aucun filtre côté app n'est nécessaire, et aucun ne doit être ajouté : il
 * parierait sur une règle qui vit ici.
 *
 * <p><b>{@code role} : à quel titre la séance est proposée.</b> « Tu étais à ta
 * propre séance ? » et « tu étais à la séance de quelqu'un d'autre ? » ne se
 * posent pas de la même façon, et la première a l'air d'une erreur quand rien ne
 * dit qu'on en est l'organisateur. Les deux valeurs sont exclusives : on ne peut
 * ni rejoindre son propre créneau ni s'inscrire à son propre programme, donc un
 * hôte n'est jamais aussi participant.
 */
@Schema(description = "Une séance terminée en attente de confirmation de présence.")
public record PendingAttendanceDto(
    UUID scheduleId,
    String programTitle,
    String placeName,

    @Schema(description = "Début de la séance à confirmer — celle qui vient de se "
        + "terminer, pas celle que porte la ligne du créneau.")
    Instant startsAt,

    @Schema(description = "Fin de la séance à confirmer. Nulle quand aucune fin n'a "
        + "jamais été déclarée sur le créneau.")
    Instant endsAt,

    @Schema(description = "À quel titre cette séance est proposée : HOST si on l'organise, "
        + "PARTICIPANT si on s'y est inscrit (par le créneau ou par le programme).",
        allowableValues = {"HOST", "PARTICIPANT"})
    String role
) {}
