package org.program.pair.domain.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Service de notification push no-op (fallback quand Firebase est désactivé)
 */
@Service
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpPushNotificationService implements PushNotificationServiceInterface {

    @Override
    public void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long unreadCount) {
        log.debug("Push notification not sent (Firebase disabled): type={}, userId={}, unreadCount={}",
            type, userId, unreadCount);
        // Ne rien faire - Firebase est désactivé
    }
}
