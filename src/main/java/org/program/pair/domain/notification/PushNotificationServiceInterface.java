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
     * @param userId  L'ID de l'utilisateur
     * @param type    Le type de notification
     * @param payload Les données de la notification
     */
    void sendPush(UUID userId, NotificationType type, Map<String, Object> payload);
}
