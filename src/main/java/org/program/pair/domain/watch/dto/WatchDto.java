package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;

import java.time.Instant;
import java.util.UUID;

/**
 * Une veille telle que son propriétaire la voit.
 *
 * <p>Le miroir de l'entité, moins {@code userId} — c'est l'appelant, le répéter
 * n'apprend rien. Rendu au seul propriétaire de la veille : ce sont ses propres
 * données.
 */
@Schema(description = "Une veille retour.")
public record WatchDto(

    UUID id,
    UUID scheduleId,
    WatchState state,
    Instant armedAt,

    @Schema(description = "Quand l'arrivée sur place a été validée. Null tant qu'elle ne l'est pas.")
    Instant arrivalConfirmedAt,

    Instant interruptedAt,

    @Schema(description = "Heure limite de retour, figée à l'armement.")
    Instant deadlineAt,

    @Schema(description = "Nombre de rappels de retour envoyés, de 0 à 3.")
    int remindersSent,

    UUID guardianId,
    UUID backupGuardianId,
    Instant closedAt,
    Instant createdAt
) {

    public static WatchDto from(Watch w) {
        return new WatchDto(
            w.getId(),
            w.getScheduleId(),
            w.getState(),
            w.getArmedAt(),
            w.getArrivalConfirmedAt(),
            w.getInterruptedAt(),
            w.getDeadlineAt(),
            w.getRemindersSent(),
            w.getGuardianId(),
            w.getBackupGuardianId(),
            w.getClosedAt(),
            w.getCreatedAt());
    }
}
