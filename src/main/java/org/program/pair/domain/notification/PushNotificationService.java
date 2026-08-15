package org.program.pair.domain.notification;

import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.LocaleConfig;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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

    /**
     * Catégorie APNs que l'extension Notification Content d'iOS déclare gérer.
     * Contractuelle avec le client : la changer ici sans la changer là-bas rend
     * l'extension muette.
     */
    static final String APNS_TEMPLATE_CATEGORY = "MEETDO_TEMPLATE";

    /**
     * Budget de la charge de données, en octets. Sous les 4 Ko d'APNs : le reste
     * couvre l'enveloppe FCM, le bloc {@code aps} et les en-têtes, qu'on ne
     * mesure pas ici. Une marge large coûte une clé décorative ; une marge trop
     * juste coûte la notification entière.
     */
    static final int DATA_PAYLOAD_BUDGET_BYTES = 3_000;

    /**
     * Ordre de sacrifice quand la charge déborde — du plus décoratif au moins.
     *
     * <p><b>Ce que la liste ne contient pas est le vrai contenu de la règle.</b>
     * {@code placeName} en est sorti à la demande du client (réponse du 15/08,
     * question 2) : un nom de lieu fait une trentaine de caractères et le perdre
     * <i>vide une zone</i> de la carte, tandis qu'{@code addressPublic} monte à
     * 300 en base et le perdre ne coûte qu'une <i>précision</i>. Sacrifier le
     * premier libérait presque rien pour le prix le plus élevé.
     *
     * <p>Ne doivent jamais y entrer, pour la même raison : {@code programTitle},
     * {@code activityName} et {@code placeName}, qui vident chacun une zone ;
     * {@code sessionAt}, qui emporte d'un coup la date, l'heure et le rebours ;
     * {@code type} et les identifiants, qui cassent le routage du tap.
     *
     * <p>Plafond toujours dépassé après ces trois évictions : c'est une charge
     * anormale, pas un cas à dégrader en silence — d'où l'{@code ERROR} de
     * {@link #dataPayload}, et l'envoi tenté quand même.
     */
    static final List<String> EVICTABLE_KEYS = List.of(
        "authorAvatarUrl",   // repli client : initiales sur pastille
        "welcomeNote",       // le client ne l'affiche nulle part
        "addressPublic");    // repli client : la carte garde le nom du lieu

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;
    private final Messages messages;
    private final AndroidPushText androidText;

    public PushNotificationService(FirebaseMessaging firebaseMessaging,
                                    DeviceTokenRepository deviceTokenRepository,
                                    Messages messages,
                                    AndroidPushText androidText) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
        this.messages = messages;
        this.androidText = androidText;
    }

    /**
     * Ce qui distingue deux textes pour un même destinataire.
     *
     * <p>Le groupement portait sur la seule langue. Il porte désormais aussi sur
     * la <b>variante de texte</b>, parce qu'Android suit la formule du template
     * (T5) et qu'iOS garde le texte traduit — devenu son repli depuis que
     * l'extension de service réécrit la bannière sur l'appareil ; et sur le
     * <b>fuseau</b>, parce que la formule Android écrit une heure : deux
     * appareils en français, l'un à Paris et l'autre à Londres, ne peuvent plus
     * partager un envoi.
     *
     * <p>La variante, et non la plateforme : iOS et le web reçoivent le même
     * texte, et les grouper séparément coûterait un envoi FCM de plus pour un
     * contenu identique.
     *
     * <p>Le fuseau est <b>résolu</b> et non brut — deux appareils, l'un déclarant
     * {@code Europe/Paris} et l'autre rien du tout, composent la même heure dès
     * lors que la référence est Paris, et doivent donc rester dans le même
     * envoi.
     */
    private record TextGroup(Locale locale, TextVariant variant, ZoneId zone) {
    }

    private enum TextVariant {
        /** Formule du template client, composée par {@link AndroidPushText}. */
        ANDROID,
        /** Texte traduit d'origine — iOS (repli de son extension) et web. */
        DEFAULT
    }

    /**
     * Envoyer une notification push à un utilisateur
     */
    @Override
    public void sendPush(UUID userId, NotificationType type, Map<String, Object> payload, long badgeCount) {
        List<DeviceToken> devices = deviceTokenRepository.findByUserId(userId);

        if (devices.isEmpty()) {
            log.debug("No device tokens found for user {}", userId);
            return;
        }

        int badge = badgeValue(badgeCount);

        // Un même utilisateur peut avoir des appareils en des langues différentes,
        // dans des fuseaux différents, et Android ne reçoit pas le même texte
        // qu'iOS : un envoi par triplet (langue, variante, fuseau), chacun avec
        // son texte. LinkedHashMap pour un ordre d'envoi déterministe.
        Map<TextGroup, List<String>> tokensByGroup = devices.stream()
            .collect(Collectors.groupingBy(
                this::textGroup,
                LinkedHashMap::new,
                Collectors.mapping(DeviceToken::getToken, Collectors.toList())));

        for (Map.Entry<TextGroup, List<String>> group : tokensByGroup.entrySet()) {
            List<String> tokens = group.getValue();
            sendToTokens(userId, tokens,
                title(group.getKey(), type, payload),
                body(group.getKey(), type, payload),
                payload, badge);
        }
    }

    private TextGroup textGroup(DeviceToken device) {
        TextVariant variant = device.getPlatform() == DevicePlatform.ANDROID
            ? TextVariant.ANDROID
            : TextVariant.DEFAULT;
        return new TextGroup(deviceLocale(device), variant, androidText.zoneOf(device.getTimezone()));
    }

    /**
     * Titre du groupe. {@link AndroidPushText} rend {@code null} pour ce qui
     * n'est pas dans le template du client — un badge gagné, un message direct
     * sans programme —, et le texte traduit d'origine reprend la main. Aucune
     * notification ne perd son titre parce qu'elle n'est pas dans la maquette.
     */
    private String title(TextGroup group, NotificationType type, Map<String, Object> payload) {
        if (group.variant() == TextVariant.ANDROID) {
            String composed = androidText.title(group.locale(), payload);
            if (composed != null) {
                return composed;
            }
        }
        return buildTitle(group.locale(), type, payload);
    }

    /** Même repli que {@link #title} : hors du template, le texte traduit d'origine. */
    private String body(TextGroup group, NotificationType type, Map<String, Object> payload) {
        if (group.variant() == TextVariant.ANDROID) {
            String composed = androidText.body(group.locale(), group.zone(), type, payload);
            if (composed != null) {
                return composed;
            }
        }
        return buildBody(group.locale(), type, payload);
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
            .putAllData(dataPayload(payload, title, body))
            // Le badge porte le total réel du destinataire — notifications ET
            // messages non lus, ce qui part compris. Application fermée, aucun
            // code client ne s'exécute pour aller le chercher, et un champ absent
            // n'est pas neutre : iOS conserve alors la valeur précédente.
            .setApnsConfig(ApnsConfig.builder()
                .setAps(visibleAps(badge))
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

        dispatch(userId, tokens, message);
    }

    /**
     * Push silencieux : corrige le badge sans rien afficher.
     *
     * <p>Trois choses en font une push « de fond » et non une notification muette :
     * l'absence de {@code alert} (donc rien à afficher), {@code content-available}
     * (qui réveille l'app), et l'en-tête {@code apns-push-type: background} —
     * exigé par APNs depuis iOS 13, faute de quoi la push est <b>rejetée</b>.
     * {@code apns-priority: 5} est l'autre exigence de ce type : une push de fond
     * en priorité 10 est refusée elle aussi.
     *
     * <p>Aucun groupement par langue ici : sans texte, il n'y a pas de langue.
     *
     * <p>Côté Android, la charge est une charge de données pure — {@code badge}
     * y voyage comme donnée, à l'application de la poser. {@code notification_count}
     * n'existe que sur une notification affichée, ce que précisément on ne veut pas.
     *
     * <p><b>Ni {@code mutable-content} ni {@code category} ici</b>, contrairement
     * à {@link #sendToTokens}. Les deux réveillent l'extension Notification
     * Content d'iOS pour qu'elle enrichisse un {@code alert} — or il n'y en a pas :
     * l'extension serait invoquée pour décorer une notification qui ne s'affiche
     * pas. Ces deux clés appartiennent aux pushes visibles, et à elles seules.
     */
    @Override
    public void sendBadgeUpdate(UUID userId, long badgeCount) {
        List<String> tokens = deviceTokenRepository.findTokensByUserId(userId);

        if (tokens.isEmpty()) {
            log.debug("No device tokens found for user {}", userId);
            return;
        }

        int badge = badgeValue(badgeCount);

        MulticastMessage message = MulticastMessage.builder()
            .addAllTokens(tokens)
            .putData("badge", String.valueOf(badge))
            .setApnsConfig(ApnsConfig.builder()
                .putHeader("apns-push-type", "background")
                .putHeader("apns-priority", "5")
                .setAps(silentAps(badge))
                .build())
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.NORMAL)
                .build())
            .build();

        dispatch(userId, tokens, message);
    }

    /**
     * Bloc {@code aps} d'une push <b>visible</b>.
     *
     * <p>{@code mutable-content} et {@code category} sont les deux clés sans
     * lesquelles l'extension Notification Content d'iOS ne se déclenche pas. Elles
     * sont posées avant que l'extension existe, et sont inertes d'ici là : l'ordre
     * inverse ferait d'elle du code mort le jour de sa livraison.
     */
    static Aps visibleAps(int badge) {
        return Aps.builder()
            .setBadge(badge)
            .setSound("default")
            .setMutableContent(true)
            .setCategory(APNS_TEMPLATE_CATEGORY)
            .build();
    }

    /**
     * Charge de données de la push, bornée pour ne pas faire rejeter l'envoi.
     *
     * <p>APNs plafonne la charge entière à 4 Ko et <b>rejette</b> ce qui dépasse :
     * l'utilisateur ne reçoit alors rien, et rien ne le signale. Tronquer l'aperçu
     * d'un message ne suffit pas à s'en prémunir — le payload métier voyage ici en
     * entier, et il porte désormais une adresse (300 caractères en base) et une
     * URL d'avatar. Le risque s'est déplacé du message vers son contexte.
     *
     * <p>Ce qui saute, saute dans l'ordre de {@link #EVICTABLE_KEYS} : le plus
     * décoratif d'abord, et jamais de quoi identifier la séance. Le client a un
     * repli documenté pour chacune de ces clés ; il n'en a aucun pour une
     * notification qui n'arrive pas.
     */
    static Map<String, String> dataPayload(Map<String, Object> payload, String title, String body) {
        Map<String, String> data = new LinkedHashMap<>();
        payload.forEach((key, value) -> data.put(key, String.valueOf(value)));

        // Le texte affiché voyage aussi dans la charge et compte dans le plafond.
        int overhead = utf8Length(title) + utf8Length(body);

        for (String key : EVICTABLE_KEYS) {
            if (overhead + serializedSize(data) <= DATA_PAYLOAD_BUDGET_BYTES) {
                break;
            }
            if (data.remove(key) != null) {
                log.warn("Push payload over budget: dropped '{}' to fit the APNs 4 KB limit", key);
            }
        }

        // Plus rien à sacrifier : on envoie quand même. Une push rejetée par APNs
        // et une push amputée de son identité se valent côté utilisateur, mais la
        // seconde laisse une trace exploitable.
        if (overhead + serializedSize(data) > DATA_PAYLOAD_BUDGET_BYTES) {
            log.error("Push payload still over budget after eviction ({} bytes) — APNs may reject it",
                overhead + serializedSize(data));
        }
        return data;
    }

    /** Taille approchée de la charge : clés et valeurs, en octets UTF-8. */
    private static int serializedSize(Map<String, String> data) {
        return data.entrySet().stream()
            .mapToInt(e -> utf8Length(e.getKey()) + utf8Length(e.getValue()))
            .sum();
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Bloc {@code aps} d'une push <b>silencieuse</b> : ni {@code mutable-content}
     * ni {@code category}, à la différence de {@link #visibleAps}. Les deux
     * réveillent l'extension d'iOS pour qu'elle enrichisse un {@code alert} — or
     * il n'y en a pas ici, et il ne doit pas y en avoir.
     */
    static Aps silentAps(int badge) {
        return Aps.builder()
            .setContentAvailable(true)
            .setBadge(badge)
            .build();
    }

    /**
     * Envoie, puis <b>rend compte de ce qui a échoué</b>.
     *
     * <p>Ce point journalisait {@code "Successfully sent {n} push notifications"}
     * au niveau INFO, y compris quand {@code n} valait zéro : un rejet de la
     * totalité des messages produisait une ligne qui se déclarait réussie. Et
     * {@link #cleanInvalidTokens} ne parlait que des deux codes qui valent
     * suppression du jeton — les cinq autres que le SDK peut rendre
     * ({@code THIRD_PARTY_AUTH_ERROR}, {@code SENDER_ID_MISMATCH},
     * {@code QUOTA_EXCEEDED}, {@code UNAVAILABLE}, {@code INTERNAL}) ne
     * laissaient aucune trace.
     *
     * <p>{@code FirebaseMessagingException} ne couvre pas ce cas : elle n'est
     * levée que si l'<i>appel</i> échoue, pas si chaque message est rejeté
     * individuellement. Une configuration APNs absente côté projet Firebase —
     * la clé {@code .p8} manquante ou visant le mauvais environnement — est
     * exactement cela : FCM accepte, APNs rejette, et le serveur ne le disait
     * pas.
     *
     * <p>D'où la ventilation par code d'erreur : c'est elle qui distingue un
     * jeton périmé d'un projet mal configuré, et l'une se corrige sur le
     * téléphone quand l'autre se corrige dans la console.
     */
    private void dispatch(UUID userId, List<String> tokens, MulticastMessage message) {
        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            int sent = response.getSuccessCount();
            int failed = response.getFailureCount();

            if (failed == 0) {
                log.info("Sent {} push notifications to user {}", sent, userId);
            } else {
                // WARN et non INFO : c'est la ligne qu'on cherche quand un
                // téléphone ne reçoit rien, et elle doit se distinguer du bruit.
                log.warn("Push delivery to user {}: {} sent, {} failed — {}",
                    userId, sent, failed, failureBreakdown(response));
            }

            // Nettoyer les tokens invalides
            cleanInvalidTokens(tokens, response);
        } catch (FirebaseMessagingException e) {
            log.error("Error sending push notifications to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Les codes d'erreur rencontrés et leur nombre, par exemple
     * {@code THIRD_PARTY_AUTH_ERROR=2}.
     *
     * <p>Le code, jamais le jeton : ces lignes partent dans les journaux d'un
     * hébergeur, et un jeton d'appareil permet d'envoyer une notification à
     * quelqu'un. {@link #cleanInvalidTokens} n'en imprime déjà que les dix
     * premiers caractères, pour la même raison.
     *
     * <p>{@code UNKNOWN} recouvre un échec sans exception ou sans code —
     * possible selon le SDK, et une catégorie visible vaut mieux qu'un total
     * qui ne se recompose pas.
     */
    private static String failureBreakdown(BatchResponse response) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SendResponse sendResponse : response.getResponses()) {
            if (sendResponse.isSuccessful()) {
                continue;
            }
            counts.merge(errorCodeOf(sendResponse), 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));
    }

    private static String errorCodeOf(SendResponse sendResponse) {
        FirebaseMessagingException exception = sendResponse.getException();
        if (exception == null || exception.getMessagingErrorCode() == null) {
            return "UNKNOWN";
        }
        return exception.getMessagingErrorCode().name();
    }

    /**
     * Valeur du badge d'icône, bornée à ce que les deux plateformes acceptent.
     *
     * <p>{@code aps.badge} et {@code notification_count} sont des entiers positifs :
     * un compteur négatif est impossible, mais un compteur qui déborderait de
     * {@code int} ferait échouer l'envoi entier. Zéro est une valeur utile et non
     * un cas dégradé — c'est ainsi qu'on efface le badge.
     */
    private int badgeValue(long badgeCount) {
        return (int) Math.max(0, Math.min(badgeCount, Integer.MAX_VALUE));
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
            case NEW_MESSAGE -> msg(locale, "push.NEW_MESSAGE.title", arg(payload, "messageAuthorName"));
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
            // Le programme, pas l'auteur : dans un fil de diffusion c'est le
            // programme qu'on suit, et trente personnes n'ont pas toutes son
            // auteur en tête.
            case PROGRAM_BROADCAST -> msg(locale, "push.PROGRAM_BROADCAST.title", arg(payload, "programTitle"));
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
            case NEW_MESSAGE -> rawOr(payload, "messageBody", locale, "push.NEW_MESSAGE.body");
            case NEW_MATCH -> msg(locale, "push.NEW_MATCH.body");
            case BADGE_EARNED -> msg(locale, "push.BADGE_EARNED.body", arg(payload, "badgeName"));
            case PROGRAM_REVIEW -> msg(locale, "push.PROGRAM_REVIEW.body");
            case PEER_RECOMMENDATION -> msg(locale, "push.PEER_RECOMMENDATION.body");
            case PROGRAM_REMINDER -> msg(locale, "push.PROGRAM_REMINDER.body", timeUntil(payload));
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
            case PROGRAM_BROADCAST -> rawOr(payload, "messageBody", locale, "push.PROGRAM_BROADCAST.body");
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

    /**
     * Temps restant avant la séance, pour le corps d'un rappel.
     *
     * <p>Aucun émetteur ne posait {@code timeUntil} : le corps traduit
     * « Votre session commence dans {0} » sortait donc amputé de son argument. La
     * valeur est dérivée de {@code sessionAt}, la clé que le payload porte déjà.
     *
     * <p><b>Calculée à l'émission, et elle vieillit.</b> Le texte d'une push est
     * figé dans la charge : aucun code client ne repassera le corriger, à la
     * différence de la carte in-app qui recalcule son compte à rebours à chaque
     * rendu. Une push restée deux heures dans le centre de notifications affiche
     * donc une valeur périmée. C'est le point que nous avons soulevé en N3 et qui
     * reste à trancher — une formulation absolue (« à 20:00 ») ne vieillirait pas.
     *
     * <p>{@code h} et {@code min} s'écrivent de la même façon dans les trois
     * langues servies : la valeur reste hors des fichiers de traduction tant que
     * c'est vrai.
     */
    private static String timeUntil(Map<String, Object> payload) {
        Object explicit = payload.get("timeUntil");
        if (explicit != null) {
            return String.valueOf(explicit);
        }
        if (!(payload.get("sessionAt") instanceof String text)) {
            return "";
        }
        try {
            Duration left = Duration.between(Instant.now(), Instant.parse(text));
            if (left.isNegative()) {
                return "";
            }
            long minutes = left.toMinutes();
            return minutes >= 60 ? (minutes / 60) + " h" : minutes + " min";
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    /** Argument de MessageFormat : jamais nul, pour ne pas imprimer « null ». */
    private static String arg(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : Objects.toString(value);
    }
}
