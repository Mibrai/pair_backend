package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Un chevauchement, décrit des deux côtés : l'occurrence du créneau demandé, et
 * celle de l'engagement déjà pris qui l'empêche.
 *
 * <p>Le client affiche une ligne par conflit avec un bouton « quitter ». Quitter
 * n'emprunte pas la même route selon la nature de l'engagement, d'où
 * {@code conflictingEngagementType} et l'identifiant qui va avec : une
 * inscription à un programme se quitte par
 * {@code POST /api/programs/{programId}/leave} avec le
 * {@code conflictingUserProgramId} dans le corps, un RSVP sur un créneau par
 * {@code DELETE /api/slots/{scheduleId}/join}. Sans ces deux champs, le client
 * devrait deviner la route puis retrouver l'identifiant de l'inscription par un
 * appel supplémentaire.
 */
public record ScheduleConflictDto(

    @Schema(description = "Créneau du programme que l'utilisateur cherche à rejoindre.")
    UUID scheduleId,

    @Schema(description = "Occurrence de ce créneau qui tombe en conflit, en UTC. Pour un "
        + "créneau récurrent, ce n'est pas nécessairement la prochaine séance.")
    Instant occurrenceAt,

    @Schema(description = "Créneau déjà rejoint qui s'y oppose.")
    UUID conflictingScheduleId,

    UUID conflictingProgramId,
    String conflictingProgramTitle,

    @Schema(description = "Occurrence de l'engagement existant qui recouvre occurrenceAt.")
    Instant conflictingOccurrenceAt,

    @Schema(description = "Fin de cette occurrence. Calculée depuis endsAt quand il est "
        + "renseigné, sinon depuis la durée de séance déclarée sur le programme, sinon "
        + "sur une convention de 60 minutes — voir ScheduleConflictDetector.")
    Instant conflictingEndsAt,

    @Schema(description = "PROGRAM (inscription à un programme) ou SLOT (participation à "
        + "un créneau ouvert). Détermine la route à appeler pour s'en défaire.",
        allowableValues = {"PROGRAM", "SLOT"})
    String conflictingEngagementType,

    @Schema(description = "Identifiant de l'inscription à passer dans le corps de "
        + "POST /programs/{programId}/leave. Nul quand conflictingEngagementType vaut "
        + "SLOT : ce cas se quitte par DELETE /slots/{scheduleId}/join, qui n'en a pas "
        + "besoin.")
    UUID conflictingUserProgramId
) {}
