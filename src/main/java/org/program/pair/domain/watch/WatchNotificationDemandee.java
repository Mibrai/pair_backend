package org.program.pair.domain.watch;

import org.program.pair.domain.notification.NotificationType;

import java.util.Map;
import java.util.UUID;

/**
 * Une notification de veille à faire partir — <b>une fois l'état validé</b>.
 *
 * <p><b>Pourquoi un événement plutôt qu'un appel direct.</b> Tout ce que
 * {@link WatchEscalationService} fait sortir accompagne un changement d'état :
 * un rappel accompagne {@code remindersSent + 1}, une alerte accompagne
 * {@code ESCALATED}, une non-arrivée accompagne {@code NOT_ARRIVED}. Appelée dans
 * la transaction, {@code notificationService.notify} est {@code @Async} : elle
 * part donc <b>avant</b> le commit, et rien ne la rattrape s'il n'a pas lieu. Le
 * défaut était visible : les boucles perdaient tout un passage sur une
 * {@code UnexpectedRollbackException} alors que les pushs étaient déjà chez les
 * gens — un rappel « ton retour n'est pas confirmé » pour une veille dont le
 * compteur de rappels venait d'être annulé, donc répété au passage suivant.
 *
 * <p>L'écouteur attend le commit (voir {@link WatchNotificationListener}). Une
 * transaction qui échoue ne fait donc plus rien partir, et c'est la définition
 * même de « aucune notification pour un changement d'état non validé ».
 *
 * <p><b>Un événement par destinataire.</b> Chacun a sa langue, ses appareils, sa
 * préférence et son propre total de non-lus : il n'y a rien de commun à
 * factoriser, et un envoi qui échoue ne doit pas emporter les autres.
 *
 * @param actorId qui a déclenché — {@code null} quand personne ne l'a provoquée,
 *                ce qui est le cas de tout ce que les minuteurs émettent. Le
 *                filtre de blocage s'appuie dessus et traite {@code null} comme
 *                « personne » (voir {@code BlockFilterService.blocked}).
 */
public record WatchNotificationDemandee(
    UUID userId,
    UUID actorId,
    NotificationType type,
    Map<String, Object> payload
) {

    /**
     * Une notification que l'horloge émet, et non quelqu'un : rappels, demandes
     * d'arrivée, alertes d'escalade, verdict de non-arrivée.
     */
    public static WatchNotificationDemandee parLHorloge(
            UUID userId, NotificationType type, Map<String, Object> payload) {
        return new WatchNotificationDemandee(userId, null, type, payload);
    }
}
