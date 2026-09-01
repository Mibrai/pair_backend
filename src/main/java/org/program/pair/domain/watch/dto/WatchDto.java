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
 *
 * <p><b>Ce que la veille dit de son propre lien public.</b> Le contrat décrivait
 * la page telle que le <i>contact</i> la voit ; il ne disait pas ce que la veille
 * en rapporte à sa <i>propriétaire</i>. Or c'est elle qui doit pouvoir envoyer le
 * lien, savoir qu'il existe, et le couper. On rend donc ici le jeton (et l'URL) du
 * lien public — nuls tant que l'alerte n'est pas partie, puisque le lien naît avec
 * elle — et les deux accusés du contact, datés : l'information la plus rassurante
 * du module, quelqu'un a vu et a réagi. Le client lit ces champs en tolérance ;
 * c'est l'<b>absence du jeton</b>, jamais l'état de la veille, qui fait qu'il n'y a
 * pas de lien à montrer.
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
    Instant createdAt,

    @Schema(description = "Jeton du lien public de statut. Null tant que l'alerte n'est pas partie. "
        + "Son absence — jamais l'état — dit qu'il n'y a pas de lien à montrer.")
    String publicToken,

    @Schema(description = "URL complète de la page de statut publique, si le jeton existe.")
    String publicStatusUrl,

    @Schema(description = "Quand le contact a cliqué « j'ai vu ». Null sinon.")
    Instant guardianSeenAt,

    @Schema(description = "Quand le contact a cliqué « je l'ai eue au téléphone ». Null sinon.")
    Instant guardianCalledAt
) {

    /**
     * @param publicBaseUrl la racine des liens publics, pour composer l'URL de statut.
     */
    public static WatchDto from(Watch w, String publicBaseUrl) {
        String token = w.getPublicToken();
        String url = token == null ? null : publicBaseUrl + "/public/watch/" + token;
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
            w.getCreatedAt(),
            token,
            url,
            w.getGuardianSeenAt(),
            w.getGuardianCalledAt());
    }
}
