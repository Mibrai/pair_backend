package org.program.pair.domain.notification;

import java.util.Map;
import java.util.UUID;

/**
 * Interface pour le service de notification push
 * Permet d'avoir une implémentation no-op quand Firebase est désactivé
 */
public interface PushNotificationServiceInterface {

    /**
     * Envoyer une notification push à un utilisateur
     *
     * @param userId       L'ID de l'utilisateur
     * @param type         Le type de notification
     * @param payload      Les données de la notification
     * @param badgeCount   Valeur du badge d'icône du destinataire — <b>notifications
     *                     non lues + messages non lus</b>, ce qui part compris (voir
     *                     {@link UnreadCounter}). Posé sur la charge elle-même
     *                     ({@code aps.badge}, {@code notification_count}) parce que
     *                     c'est le seul moyen de tenir le badge à jour
     *                     <b>application fermée</b> : aucun code client ne tourne
     *                     alors pour aller le lire.
     */
    void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long badgeCount);

    /**
     * Corriger le badge d'icône sans rien afficher.
     *
     * <p>Push silencieux ({@code content-available}, sans {@code alert}) : l'écran
     * ne s'allume pas, aucune ligne n'apparaît dans le centre de notifications,
     * seul le nombre sur l'icône change. C'est ce qu'il faut quand la lecture a eu
     * lieu sur un autre appareil et qu'aucune notification n'est à annoncer.
     *
     * @param badgeCount la valeur à afficher, {@code 0} pour effacer le badge
     */
    void sendBadgeUpdate(UUID userId, long badgeCount);
}
