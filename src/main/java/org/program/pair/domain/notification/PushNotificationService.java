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
    public void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long unreadCount) {
        List<String> tokens = deviceTokenRepository.findTokensByUserId(userId);

        if (tokens.isEmpty()) {
            log.debug("No device tokens found for user {}", userId);
            return;
        }

        String title = buildTitle(type, payload);
        String body = buildBody(type, payload);
        int badge = badgeValue(unreadCount);

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
            // Le badge valait 1 en dur : l'icône affichait « 1 » quel que soit le
            // nombre réel de notifications en attente, et n'était juste que dans le
            // cas d'une seule. C'est le compteur du destinataire, celle qui part
            // comprise, qui doit voyager avec la charge — application fermée, aucun
            // code client ne s'exécute pour aller le chercher.
            .setApnsConfig(ApnsConfig.builder()
                .setAps(Aps.builder()
                    .setBadge(badge)
                    .setSound("default")
                    .build())
                .build())
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                    .setSound("default")
                    .setColor("#FF5722")
                    .setNotificationCount(badge)
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
     * Valeur du badge d'icône, bornée à ce que les deux plateformes acceptent.
     *
     * <p>{@code aps.badge} et {@code notification_count} sont des entiers positifs :
     * un compteur négatif est impossible, mais un compteur qui déborderait de
     * {@code int} ferait échouer l'envoi entier. Zéro est une valeur utile et non
     * un cas dégradé — c'est ainsi qu'on efface le badge.
     */
    private int badgeValue(long unreadCount) {
        return (int) Math.max(0, Math.min(unreadCount, Integer.MAX_VALUE));
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
            case AUTHOR_NEW_ACTIVITY -> payload.get("authorName") + " a créé une nouvelle activité";
            case AUTHOR_NEW_PROGRAM -> payload.get("authorName") + " a créé un nouveau programme";
            case ACTIVITY_UPDATED -> "Une activité que vous suivez a changé";
            case ACTIVITY_NEW_PROGRAM -> "Nouveau programme sur une activité que vous suivez";
            case CATEGORY_NEW_ACTIVITY -> "Nouvelle activité dans une catégorie suivie";
            // Valeurs legacy utilisées uniquement par les données de seed (V12/V13/V27) —
            // jamais émises par notify(), donc pas de titre push dédié.
            default -> "Nouvelle notification";
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
            case AUTHOR_NEW_ACTIVITY -> String.valueOf(payload.get("activityName"));
            case AUTHOR_NEW_PROGRAM -> String.valueOf(payload.get("programTitle"));
            case ACTIVITY_UPDATED -> String.valueOf(payload.get("activityName"));
            case ACTIVITY_NEW_PROGRAM -> String.valueOf(payload.get("programTitle"));
            case CATEGORY_NEW_ACTIVITY -> String.valueOf(payload.get("activityName"));
            // Valeurs legacy utilisées uniquement par les données de seed (V12/V13/V27) —
            // jamais émises par notify(), donc pas de corps push dédié.
            default -> "Vous avez une nouvelle notification";
        };
    }
}
