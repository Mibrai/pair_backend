package org.program.pair.domain.notification;

import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.LocaleConfig;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Envoi des notifications push (FCM), dans la langue de chaque appareil.
 *
 * <p><b>Pourquoi le texte est ici.</b> L'API n'envoie ni {@code title} ni
 * {@code message} sur les notifications in-app — le client compose (décision
 * B10). Une push, elle, s'affiche sur un téléphone verrouillé, avant tout code
 * client : le texte doit voyager dans la charge, et c'est le seul endroit où le
 * serveur rédige encore.
 *
 * <p><b>Pourquoi la langue vient de l'appareil.</b> {@code Accept-Language} dit
 * la langue de la requête <i>en cours</i> — or une push est émise par la requête
 * de quelqu'un d'autre (rejoindre votre créneau), ou par un job planifié qui n'a
 * aucune requête. La langue est donc lue sur {@code device_tokens.locale},
 * posée à l'enregistrement du token, appareil par appareil. Les tokens sont
 * groupés par langue et un message part par groupe.
 */
@Service
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
@Slf4j
public class PushNotificationService implements PushNotificationServiceInterface {

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;
    private final Messages messages;

    public PushNotificationService(FirebaseMessaging firebaseMessaging,
                                    DeviceTokenRepository deviceTokenRepository,
                                    Messages messages) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
        this.messages = messages;
    }

    /**
     * Envoyer une notification push à un utilisateur
     */
    @Override
    public void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long unreadCount) {
        List<DeviceToken> devices = deviceTokenRepository.findByUserId(userId);

        if (devices.isEmpty()) {
            log.debug("No device tokens found for user {}", userId);
            return;
        }

        int badge = badgeValue(unreadCount);

        // Un même utilisateur peut avoir des appareils en des langues différentes :
        // un envoi par langue, chacun avec son texte. LinkedHashMap pour un ordre
        // d'envoi déterministe.
        Map<Locale, List<String>> tokensByLocale = devices.stream()
            .collect(Collectors.groupingBy(
                PushNotificationService::deviceLocale,
                LinkedHashMap::new,
                Collectors.mapping(DeviceToken::getToken, Collectors.toList())));

        for (Map.Entry<Locale, List<String>> group : tokensByLocale.entrySet()) {
            Locale locale = group.getKey();
            List<String> tokens = group.getValue();
            sendToTokens(userId, tokens,
                buildTitle(locale, type, payload),
                buildBody(locale, type, payload),
                payload, badge);
        }
    }

    /**
     * Langue d'un appareil : celle qu'il a déclarée, sinon le français — même
     * repli qu'un {@code Accept-Language} absent, et le comportement des tokens
     * enregistrés avant l'existence de la colonne.
     */
    private static Locale deviceLocale(DeviceToken device) {
        Locale declared = LocaleConfig.closestSupported(device.getLocale());
        return declared != null ? declared : LocaleConfig.FRENCH;
    }

    private void sendToTokens(UUID userId, List<String> tokens, String title, String body,
                              Map<String, Object> payload, int badge) {
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
            // Le badge porte le compteur réel de non-lues du destinataire, celle
            // qui part comprise — application fermée, aucun code client ne
            // s'exécute pour aller le chercher.
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
     * Titre de la notification, dans la langue de l'appareil. Les clés vivent
     * dans {@code messages*.properties} ({@code push.<TYPE>.title}) — c'était du
     * français en dur ici même, illisible pour un appareil réglé en allemand.
     */
    String buildTitle(Locale locale, NotificationType type, Map<String, Object> payload) {
        return switch (type) {
            case NEW_MESSAGE -> msg(locale, "push.NEW_MESSAGE.title", arg(payload, "senderName"));
            case NEW_MATCH -> msg(locale, "push.NEW_MATCH.title");
            case BADGE_EARNED -> msg(locale, "push.BADGE_EARNED.title");
            case PROGRAM_REVIEW -> msg(locale, "push.PROGRAM_REVIEW.title");
            case PEER_RECOMMENDATION -> msg(locale, "push.PEER_RECOMMENDATION.title", arg(payload, "fromName"));
            case PROGRAM_REMINDER -> msg(locale, "push.PROGRAM_REMINDER.title", arg(payload, "programTitle"));
            case PROGRESSION_REMINDER -> msg(locale, "push.PROGRESSION_REMINDER.title");
            case NEW_FOLLOWER -> msg(locale, "push.NEW_FOLLOWER.title", arg(payload, "followerName"));
            case NEARBY_PROGRAM -> msg(locale, "push.NEARBY_PROGRAM.title");
            case ACCOUNT_VERIFICATION -> msg(locale, "push.ACCOUNT_VERIFICATION.title");
            case PASSWORD_RESET -> msg(locale, "push.PASSWORD_RESET.title");
            case MODERATION_ACTION -> msg(locale, "push.MODERATION_ACTION.title");
            case AUTHOR_NEW_ACTIVITY -> msg(locale, "push.AUTHOR_NEW_ACTIVITY.title", arg(payload, "authorName"));
            case AUTHOR_NEW_PROGRAM -> msg(locale, "push.AUTHOR_NEW_PROGRAM.title", arg(payload, "authorName"));
            case ACTIVITY_UPDATED -> msg(locale, "push.ACTIVITY_UPDATED.title");
            case ACTIVITY_NEW_PROGRAM -> msg(locale, "push.ACTIVITY_NEW_PROGRAM.title");
            case CATEGORY_NEW_ACTIVITY -> msg(locale, "push.CATEGORY_NEW_ACTIVITY.title");
            // meetDo — auparavant relégués au titre générique alors que notify()
            // les émet réellement.
            case SLOT_JOINED -> msg(locale, "push.SLOT_JOINED.title", arg(payload, "participantName"));
            case SLOT_CANCELLED -> msg(locale, "push.SLOT_CANCELLED.title", arg(payload, "programTitle"));
            case ATTENDANCE_PROMPT -> msg(locale, "push.ATTENDANCE_PROMPT.title");
            case ACTIVITY_ALERT_MATCH -> msg(locale, "push.ACTIVITY_ALERT_MATCH.title", arg(payload, "activityName"));
            // Valeurs legacy utilisées uniquement par les données de seed (V12/V13/V27) —
            // jamais émises par notify(), donc pas de titre push dédié.
            default -> msg(locale, "push.generic.title");
        };
    }

    /**
     * Corps de la notification. Certains corps sont une donnée brute du payload
     * (aperçu de message, titre de programme) : elle est affichée telle quelle,
     * la traduction ne portant que sur le repli quand elle manque.
     */
    String buildBody(Locale locale, NotificationType type, Map<String, Object> payload) {
        return switch (type) {
            case NEW_MESSAGE -> rawOr(payload, "messagePreview", locale, "push.NEW_MESSAGE.body");
            case NEW_MATCH -> msg(locale, "push.NEW_MATCH.body");
            case BADGE_EARNED -> msg(locale, "push.BADGE_EARNED.body", arg(payload, "badgeName"));
            case PROGRAM_REVIEW -> msg(locale, "push.PROGRAM_REVIEW.body");
            case PEER_RECOMMENDATION -> msg(locale, "push.PEER_RECOMMENDATION.body");
            case PROGRAM_REMINDER -> msg(locale, "push.PROGRAM_REMINDER.body", arg(payload, "timeUntil"));
            case PROGRESSION_REMINDER -> msg(locale, "push.PROGRESSION_REMINDER.body", arg(payload, "streak"));
            case NEW_FOLLOWER -> msg(locale, "push.NEW_FOLLOWER.body");
            case NEARBY_PROGRAM -> rawOr(payload, "programTitle", locale, "push.NEARBY_PROGRAM.body");
            case ACCOUNT_VERIFICATION -> msg(locale, "push.ACCOUNT_VERIFICATION.body");
            case PASSWORD_RESET -> msg(locale, "push.PASSWORD_RESET.body");
            case MODERATION_ACTION -> rawOr(payload, "message", locale, "push.MODERATION_ACTION.body");
            case AUTHOR_NEW_ACTIVITY, CATEGORY_NEW_ACTIVITY, ACTIVITY_UPDATED ->
                rawOr(payload, "activityName", locale, "push.generic.body");
            case AUTHOR_NEW_PROGRAM, ACTIVITY_NEW_PROGRAM ->
                rawOr(payload, "programTitle", locale, "push.generic.body");
            case SLOT_JOINED -> rawOr(payload, "programTitle", locale, "push.generic.body");
            case SLOT_CANCELLED -> msg(locale, "push.SLOT_CANCELLED.body", arg(payload, "placeName"));
            case ATTENDANCE_PROMPT -> msg(locale, "push.ATTENDANCE_PROMPT.body", arg(payload, "programTitle"));
            case ACTIVITY_ALERT_MATCH -> msg(locale, "push.ACTIVITY_ALERT_MATCH.body",
                arg(payload, "activityName"), arg(payload, "placeName"));
            default -> msg(locale, "push.generic.body");
        };
    }

    private String msg(Locale locale, String key, Object... args) {
        return messages.getIn(locale, key, args);
    }

    /** Valeur du payload telle quelle, ou le texte de repli traduit si absente. */
    private String rawOr(Map<String, Object> payload, String key, Locale locale, String fallbackKey) {
        Object value = payload.get(key);
        String text = value != null ? String.valueOf(value) : null;
        return text != null && !text.isBlank() && !"null".equals(text)
            ? text
            : msg(locale, fallbackKey);
    }

    /** Argument de MessageFormat : jamais nul, pour ne pas imprimer « null ». */
    private static String arg(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : Objects.toString(value);
    }
}
