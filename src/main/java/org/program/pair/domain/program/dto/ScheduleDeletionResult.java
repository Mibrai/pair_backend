package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ce que {@code DELETE /api/programs/{programId}/schedules/{scheduleId}} a
 * réellement fait (P-BA-16, décision D3 option B).
 *
 * <p>La route supprime un créneau vide, mais annule — et prévient — un créneau
 * qui concerne quelqu'un. Les deux rendaient le même {@code 204} : l'app ne
 * pouvait pas dire à l'organisateur que ses inscrits venaient d'être prévenus.
 */
@Schema(description = "Issue d'une demande de suppression de créneau.")
public record ScheduleDeletionResult(

    @Schema(description = "DELETED : le créneau ne concernait personne et n'existe plus. "
        + "CANCELLED : des personnes comptaient dessus, il est annulé et elles sont "
        + "prévenues une fois — ou l'étaient déjà s'il était annulé.")
    Outcome outcome,

    @Schema(description = "Le créneau annulé. Null quand outcome vaut DELETED.")
    ScheduleDto schedule
) {

    public enum Outcome { DELETED, CANCELLED }

    public static ScheduleDeletionResult deleted() {
        return new ScheduleDeletionResult(Outcome.DELETED, null);
    }

    public static ScheduleDeletionResult cancelled(ScheduleDto schedule) {
        return new ScheduleDeletionResult(Outcome.CANCELLED, schedule);
    }
}
