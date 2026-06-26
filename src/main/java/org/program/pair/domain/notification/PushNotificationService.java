package org.program.pair.domain.notification;

import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.DeviceTokenRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
@Slf4j
public class PushNotificationService implements PushNotificationServiceInterface {

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    public PushNotificationService(FirebaseMessaging firebaseMessaging,
                                    DeviceTokenRepository deviceTokenRepository) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
    }

    /**
     * Envoyer une notification push à un utilisateur
     */
    @Override
    public void sendPush(UUID userId, NotificationType type, Map<String, Object> payload) {
        List<String> tokens = deviceTokenRepository.findTokensByUserId(userId);

        if (tokens.isEmpty()) {
            log.debug("No device tokens found for user {}", userId);
            return;
        }

        String title = buildTitle(type, payload);
        String body = buildBody(type, payload);

        MulticastMessage message = MulticastMessage.builder()
            .addAllTokens(tokens)
            .setNotification(com.google.firebase.messaging.Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build())
            .putAllData(payload.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> String.valueOf(e.getValue())
                )))
            .setApnsConfig(ApnsConfig.builder()
                .setAps(Aps.builder()
                    .setBadge(1)
                    .setSound("default")
                    .build())
                .build())
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                    .setSound("default")
                    .setColor("#FF5722")
                    .build())
                .build())
            .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            log.info("Successfully sent {} push notifications to user {}",
                response.getSuccessCount(), userId);

            // Nettoyer les tokens invalides
            cleanInvalidTokens(tokens, response);
        } catch (FirebaseMessagingException e) {
            log.error("Error sending push notifications to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Nettoyer les tokens invalides
     */
    private void cleanInvalidTokens(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();

        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);

            if (!sendResponse.isSuccessful() && sendResponse.getException() != null) {
                MessagingErrorCode errorCode = sendResponse.getException().getMessagingErrorCode();

                if (errorCode == MessagingErrorCode.UNREGISTERED ||
                    errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                    String invalidToken = tokens.get(i);
                    deviceTokenRepository.deleteByToken(invalidToken);
                    log.info("Removed invalid device token: {}", invalidToken.substring(0, 10) + "...");
                }
            }
        }
    }

    /**
     * Construire le titre de la notification
     */
    private String buildTitle(NotificationType type, Map<String, Object> payload) {
        return switch (type) {
            case NEW_MESSAGE -> "Nouveau message de " + payload.get("senderName");
            case NEW_MATCH -> "Quelqu'un partage votre passion !";
            case BADGE_EARNED -> "Nouveau badge obtenu 🎉";
            case PROGRAM_REVIEW -> "Nouvel avis sur votre programme";
            case PEER_RECOMMENDATION -> payload.get("fromName") + " vous recommande";
            case PROGRAM_REMINDER -> "Rappel : " + payload.get("programTitle");
            case PROGRESSION_REMINDER -> "N'oubliez pas votre progression !";
            case NEW_FOLLOWER -> payload.get("followerName") + " vous suit";
            case NEARBY_PROGRAM -> "Nouveau programme près de vous";
            case ACCOUNT_VERIFICATION -> "Vérification de compte";
            case PASSWORD_RESET -> "Réinitialisation de mot de passe";
            case MODERATION_ACTION -> "Action de modération";
        };
    }

    /**
     * Construire le corps de la notification
     */
    private String buildBody(NotificationType type, Map<String, Object> payload) {
        return switch (type) {
            case NEW_MESSAGE -> (String) payload.getOrDefault("messagePreview", "Vous avez un nouveau message");
            case NEW_MATCH -> "Découvrez votre nouveau match sur Pair !";
            case BADGE_EARNED -> "Vous avez gagné le badge : " + payload.get("badgeName");
            case PROGRAM_REVIEW -> "Quelqu'un a évalué votre programme";
            case PEER_RECOMMENDATION -> "Vous avez reçu une recommandation";
            case PROGRAM_REMINDER -> "Votre session commence dans " + payload.get("timeUntil");
            case PROGRESSION_REMINDER -> "Continuez votre série de " + payload.get("streak") + " jours !";
            case NEW_FOLLOWER -> "Vous avez un nouvel abonné";
            case NEARBY_PROGRAM -> (String) payload.getOrDefault("programTitle", "Un nouveau programme est disponible");
            case ACCOUNT_VERIFICATION -> "Vérifiez votre compte pour débloquer toutes les fonctionnalités";
            case PASSWORD_RESET -> "Cliquez pour réinitialiser votre mot de passe";
            case MODERATION_ACTION -> (String) payload.getOrDefault("message", "Une action a été prise sur votre contenu");
        };
    }
}
