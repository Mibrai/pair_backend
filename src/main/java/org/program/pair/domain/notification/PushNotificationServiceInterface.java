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
     * @param unreadCount  Nombre de notifications non lues du destinataire, celle
     *                     qui part comprise. Posé sur la charge elle-même
     *                     ({@code aps.badge}, {@code notification_count}) parce que
     *                     c'est le seul moyen de tenir le badge d'icône à jour
     *                     <b>application fermée</b> : aucun code client ne tourne
     *                     alors pour aller le lire.
     */
    void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long unreadCount);
}
