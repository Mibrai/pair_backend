package org.program.pair.domain.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FcmResponses;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.config.LocaleConfig;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Les textes push dans la langue de l'appareil destinataire.
 *
 * <p>Le point testé : la langue vient de {@code device_tokens.locale}, jamais
 * d'une requête — il n'y en a pas au moment d'envoyer — et un utilisateur aux
 * appareils multilingues reçoit un envoi par langue.
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock FirebaseMessaging firebaseMessaging;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock BatchResponse batchResponse;

    /**
     * Le vrai {@code MessageSource} de l'application ({@code messages*.properties}
     * du classpath), pas un stub : les tests vérifient aussi que les clés
     * existent — dans les trois langues.
     */
    private static Messages messages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(LocaleConfig.FRENCH);
        return new Messages(source);
    }

    /**
     * Horloge et fuseau imposés : le texte Android porte des dates et un rebours,
     * qui seraient sinon différents à chaque exécution.
     */
    private static AndroidPushText androidText() {
        return new AndroidPushText(messages(), ZONE, Clock.fixed(NOW, ZONE));
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    /** 17/08/2026 à 17:00 UTC, soit 19:00 à Paris. */
    private static final Instant NOW = Instant.parse("2026-08-17T15:00:00Z");

    /**
     * Le dépôt d'utilisateurs sert aux heures de silence (lot D6). Ces tests-ci
     * n'appellent que la composition des textes, qui ne le touche pas — d'où un
     * simulacre nu plutôt qu'un montage complet.
     */
    private PushNotificationService service() {
        return new PushNotificationService(firebaseMessaging, deviceTokenRepository,
            messages(), androidText(),
            org.mockito.Mockito.mock(org.program.pair.repository.UserRepository.class));
    }

    @Test
    void titreEtCorps_doiventSuivreLaLangueDeLAppareil() {
        PushNotificationService service = service();
        Map<String, Object> payload = Map.of(
            "participantName", "Max", "programTitle", "Yoga du soir");

        assertThat(service.buildTitle(LocaleConfig.FRENCH, NotificationType.SLOT_JOINED, payload))
            .isEqualTo("Max a rejoint votre créneau");
        assertThat(service.buildTitle(LocaleConfig.ENGLISH, NotificationType.SLOT_JOINED, payload))
            .isEqualTo("Max joined your slot");
        assertThat(service.buildTitle(LocaleConfig.GERMAN, NotificationType.SLOT_JOINED, payload))
            .isEqualTo("Max ist Ihrem Termin beigetreten");
    }

    @Test
    void corpsBrutDuPayload_resteBrut_etLeRepliEstTraduit() {
        PushNotificationService service = service();

        // Le corps est une donnée (titre du programme) : identique quelle que
        // soit la langue.
        Map<String, Object> withTitle = Map.of("programTitle", "Yoga du soir");
        assertThat(service.buildBody(LocaleConfig.GERMAN, NotificationType.SLOT_JOINED, withTitle))
            .isEqualTo("Yoga du soir");

        // La donnée manque : le repli, lui, est traduit.
        assertThat(service.buildBody(LocaleConfig.GERMAN, NotificationType.SLOT_JOINED, Map.of()))
            .isEqualTo("Sie haben eine neue Benachrichtigung");
    }

    @Test
    void apostropheDansUnTexteAArguments_doitSortirSimple() {
        // Piège MessageFormat : l'apostrophe est un caractère d'échappement dès
        // qu'il y a des arguments. Le texte doit sortir avec UNE apostrophe.
        PushNotificationService service = service();
        Map<String, Object> payload = Map.of("activityName", "Yoga", "placeName", "Studio Zen");

        assertThat(service.buildBody(LocaleConfig.FRENCH, NotificationType.ACTIVITY_ALERT_MATCH, payload))
            .isEqualTo("Quelqu'un propose Yoga à Studio Zen");
    }

    @Test
    void appareilsEnLanguesDifferentes_recoiventChacunLeurEnvoi() throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-fr", "fr"),
            device("token-de", "de"),
            device("token-legacy", null)   // pré-colonne : repli français
        ));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.ATTENDANCE_PROMPT,
            Map.of("programTitle", "Yoga du soir"), 3);

        // fr et legacy partagent le groupe français, de a le sien : deux envois.
        verify(firebaseMessaging, times(2)).sendEachForMulticast(any(MulticastMessage.class));
    }

    // ─── T5 — la formule Android, et le repli iOS ─────────────────────────────

    /**
     * Un Android et un iPhone dans la même langue ne reçoivent <b>pas</b> le même
     * texte : Android suit la formule du template, iOS garde le texte traduit,
     * devenu son repli depuis que son extension réécrit la bannière sur
     * l'appareil. D'où deux envois là où une seule langue est en jeu.
     */
    @Test
    void androidEtIos_memeLangue_doiventRecevoirDeuxTextesDifferents() throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-android", "fr", DevicePlatform.ANDROID),
            device("token-ios", "fr", DevicePlatform.IOS)));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging, times(2)).sendEachForMulticast(captor.capture());

        List<String> bodies = captor.getAllValues().stream()
            .map(PushNotificationServiceTest::bodyOf)
            .toList();

        // Android : la formule, composée ici, à l'horloge fixe du test.
        assertThat(bodies).contains(
            "dans 2 h · Aujourd'hui 19:00 – 20:00 · par Lena Müller\nPiscine du Rhône");

        // iOS : le texte traduit d'origine, que son extension réécrira sur
        // l'appareil. Assertion sur la forme et non sur la valeur du rebours :
        // ce chemin-là calcule encore sur l'horloge réelle, et la valeur
        // dépendrait du jour où le test tourne.
        assertThat(bodies).anyMatch(body -> body.startsWith("Votre session commence dans "));
        assertThat(bodies).hasSize(2);
    }

    /**
     * Le web n'a pas plus d'extension qu'Android, mais le client n'a pas demandé
     * la formule pour lui : il reste sur le texte traduit, et surtout il ne
     * déclenche pas un envoi de plus quand un iPhone est déjà dans la même
     * langue.
     */
    @Test
    void webEtIos_memeLangue_doiventPartirEnUnSeulEnvoi() throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-web", "fr", DevicePlatform.WEB),
            device("token-ios", "fr", DevicePlatform.IOS)));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    /**
     * Un type hors du template garde son texte traduit, y compris sur Android :
     * le branchement de la formule est additif, il ne vide rien.
     */
    @Test
    void android_surUnTypeHorsDuTemplate_doitGarderLeTexteTraduit() throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-android", "fr", DevicePlatform.ANDROID)));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.BADGE_EARNED, Map.of("badgeName", "Régularité"), 1);

        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging).sendEachForMulticast(captor.capture());
        assertThat(bodyOf(captor.getValue())).isEqualTo("Vous avez gagné le badge : Régularité");
    }

    // ─── Le fuseau de l'appareil entre dans le groupement ─────────────────────

    /**
     * Deux Android, même langue, deux fuseaux : deux envois, et deux heures.
     * Le groupement ne pouvait plus porter sur la seule langue dès lors que la
     * formule Android écrit une heure.
     */
    @Test
    void deuxFuseaux_memeLangue_doiventRecevoirDeuxHeures() throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-paris", "fr", DevicePlatform.ANDROID, "Europe/Paris"),
            device("token-londres", "fr", DevicePlatform.ANDROID, "Europe/London")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging, times(2)).sendEachForMulticast(captor.capture());

        assertThat(captor.getAllValues().stream().map(PushNotificationServiceTest::bodyOf).toList())
            .containsExactlyInAnyOrder(
                "dans 2 h · Aujourd'hui 19:00 – 20:00 · par Lena Müller\nPiscine du Rhône",
                "dans 2 h · Aujourd'hui 18:00 – 19:00 · par Lena Müller\nPiscine du Rhône");
    }

    /**
     * Un fuseau déclaré identique à la référence du serveur, et un fuseau absent,
     * composent la même heure : ils doivent donc rester dans le même envoi. C'est
     * pour cela que la clé de groupement porte le fuseau <b>résolu</b> et non
     * l'étiquette brute.
     */
    @Test
    void fuseauDeclareEgalALaReference_etFuseauAbsent_doiventPartirEnsemble()
            throws FirebaseMessagingException {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-declare", "fr", DevicePlatform.ANDROID, "Europe/Paris"),
            device("token-muet", "fr", DevicePlatform.ANDROID, null)));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    // ─── L'envoi doit rendre compte de ce qui a échoué ────────────────────────

    /**
     * Le point : un rejet par APNs ne laissait <b>aucune</b> trace. Seuls
     * {@code UNREGISTERED} et {@code INVALID_ARGUMENT} étaient journalisés, et
     * uniquement parce qu'ils suppriment le jeton ; les cinq autres codes
     * passaient en silence derrière un {@code "Successfully sent 0"} en INFO.
     *
     * <p>{@code THIRD_PARTY_AUTH_ERROR} est précisément le cas qui compte : FCM
     * accepte, APNs rejette parce que le projet n'a pas de clé d'authentification
     * valide. Rien côté serveur ne le disait, et c'est une correction de console,
     * pas de code.
     */
    @Test
    void envoiRejeteParApns_doitNommerLeCodeDErreur_enWarn() throws FirebaseMessagingException {
        Logger logger = (Logger) LoggerFactory.getLogger(PushNotificationService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);

        try {
            PushNotificationService service = service();
            UUID userId = UUID.randomUUID();

            when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
                device("token-ios-1", "fr"),
                device("token-ios-2", "fr")));
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenReturn(batchResponse);
            when(batchResponse.getSuccessCount()).thenReturn(0);
            when(batchResponse.getFailureCount()).thenReturn(2);
            when(batchResponse.getResponses()).thenReturn(List.of(
                FcmResponses.failure(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR),
                FcmResponses.failure(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)));

            service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

            assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                    .contains("0 sent", "2 failed", "THIRD_PARTY_AUTH_ERROR=2"));

            // Et surtout : plus aucune ligne ne se déclare réussie.
            assertThat(captured.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("Successfully sent"));
        } finally {
            logger.detachAppender(captured);
        }
    }

    /**
     * Un jeton périmé et un projet mal configuré ne se corrigent pas au même
     * endroit — l'un sur le téléphone, l'autre dans la console Firebase. La
     * ventilation doit donc distinguer les deux dans le même envoi.
     */
    @Test
    void echecsDeCodesDifferents_doiventEtreVentiles() throws FirebaseMessagingException {
        Logger logger = (Logger) LoggerFactory.getLogger(PushNotificationService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);

        try {
            PushNotificationService service = service();
            UUID userId = UUID.randomUUID();

            when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
                device("token-perime", "fr"),
                device("token-ok", "fr"),
                device("token-apns", "fr")));
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenReturn(batchResponse);
            when(batchResponse.getSuccessCount()).thenReturn(1);
            when(batchResponse.getFailureCount()).thenReturn(2);
            when(batchResponse.getResponses()).thenReturn(List.of(
                FcmResponses.failure(MessagingErrorCode.UNREGISTERED),
                FcmResponses.success("msg-id"),
                FcmResponses.failure(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)));

            service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

            assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                    .contains("1 sent", "2 failed", "UNREGISTERED=1", "THIRD_PARTY_AUTH_ERROR=1"));

            // Le jeton périmé est bien supprimé, celui qu'APNs rejette ne l'est
            // pas : le projet est mal configuré, l'appareil n'y est pour rien.
            verify(deviceTokenRepository).deleteByToken("token-perime");
            verify(deviceTokenRepository, never()).deleteByToken("token-apns");
        } finally {
            logger.detachAppender(captured);
        }
    }

    /** Un envoi sans échec reste en INFO : la ligne WARN doit rester rare pour être lue. */
    @Test
    void envoiSansEchec_neDoitPasAlerter() throws FirebaseMessagingException {
        Logger logger = (Logger) LoggerFactory.getLogger(PushNotificationService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);

        try {
            PushNotificationService service = service();
            UUID userId = UUID.randomUUID();

            when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(device("token", "fr")));
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenReturn(batchResponse);
            when(batchResponse.getSuccessCount()).thenReturn(1);
            when(batchResponse.getFailureCount()).thenReturn(0);
            when(batchResponse.getResponses()).thenReturn(List.of(FcmResponses.success("msg-id")));

            service.sendPush(userId, NotificationType.PROGRAM_REMINDER, slotPayload(), 1);

            assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.WARN);
        } finally {
            logger.detachAppender(captured);
        }
    }

    /** Séance à 19:00 – 20:00 heure de Paris, deux heures après {@link #NOW}. */
    private static Map<String, Object> slotPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activityName", "Natation");
        payload.put("programTitle", "Longueurs du soir");
        payload.put("authorName", "Lena Müller");
        payload.put("placeName", "Piscine du Rhône");
        payload.put("sessionAt", "2026-08-17T17:00:00Z");
        payload.put("endsAt", "2026-08-17T18:00:00Z");
        return payload;
    }

    /** Le SDK ne rend pas la notification composée : on la relit par réflexion. */
    private static String bodyOf(MulticastMessage message) {
        try {
            java.lang.reflect.Field notification = MulticastMessage.class.getDeclaredField("notification");
            notification.setAccessible(true);
            Object value = notification.get(message);
            java.lang.reflect.Field body = value.getClass().getDeclaredField("body");
            body.setAccessible(true);
            return (String) body.get(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Impossible de relire le corps du message", e);
        }
    }

    @Test
    void badgeSilencieux_doitPartirEnUnSeulEnvoi_quelleQueSoitLaLangue() throws FirebaseMessagingException {
        // Un push silencieux ne porte aucun texte : il n'y a donc pas de langue à
        // départager, et grouper par locale n'aurait aucun sens. Un seul envoi,
        // même pour des appareils en trois langues.
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findTokensByUserId(userId))
            .thenReturn(List.of("token-fr", "token-de", "token-en"));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendBadgeUpdate(userId, 0);

        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void badgeSilencieux_sansAucunAppareil_neDoitRienEnvoyer() {
        PushNotificationService service = service();
        UUID userId = UUID.randomUUID();

        when(deviceTokenRepository.findTokensByUserId(userId)).thenReturn(List.of());

        service.sendBadgeUpdate(userId, 3);

        verifyNoInteractions(firebaseMessaging);
    }

    // ─── N2 (option A) — le texte d'un message lit les clés du template ───────

    @Test
    void messagePousse_doitLireMessageAuthorNameEtMessageBody() {
        // Renommées depuis senderName/messagePreview pour suivre le template du
        // client. Si buildTitle/buildBody étaient restés sur les anciens noms, le
        // renommage aurait produit une bannière « Nouveau message de  » et un
        // corps de repli — sans qu'aucune erreur ne soit levée.
        PushNotificationService service = service();
        Map<String, Object> payload = Map.of(
            "messageAuthorName", "Sophie Martin",
            "messageBody", "On se retrouve devant le court 3 ?");

        assertThat(service.buildTitle(LocaleConfig.FRENCH, NotificationType.NEW_MESSAGE, payload))
            .contains("Sophie Martin");
        // Le corps est une donnée brute : affiché tel quel, quelle que soit la langue.
        assertThat(service.buildBody(LocaleConfig.GERMAN, NotificationType.NEW_MESSAGE, payload))
            .isEqualTo("On se retrouve devant le court 3 ?");
    }

    @Test
    void messageSansCorps_doitRetomberSurLeRepliTraduit() {
        PushNotificationService service = service();

        assertThat(service.buildBody(LocaleConfig.GERMAN, NotificationType.NEW_MESSAGE, Map.of()))
            .isNotBlank()
            .isNotEqualTo("null");
    }

    // ─── N5 — les deux clés que réclame l'extension iOS ───────────────────────

    @Test
    void pushVisible_doitPorterMutableContentEtCategory() throws Exception {
        // Sans ces deux clés, l'extension Notification Content ne se déclenche
        // pas : elle serait du code mort le jour de sa livraison. Elles sont
        // inertes tant qu'elle n'existe pas, d'où leur pose anticipée.
        Map<String, Object> fields = apsFields(PushNotificationService.visibleAps(3));

        assertThat(fields).containsEntry("category", "MEETDO_TEMPLATE");
        assertThat(fields.get("mutable-content")).isIn(1, 1L, true);
        assertThat(fields).containsEntry("badge", 3);
    }

    @Test
    void pushSilencieuse_neDoitPorterNiMutableContentNiCategory() throws Exception {
        // Une push de fond n'a pas d'alert : réveiller l'extension pour enrichir
        // ce qui ne s'affiche pas n'a pas de sens.
        Map<String, Object> fields = apsFields(PushNotificationService.silentAps(0));

        assertThat(fields).doesNotContainKeys("mutable-content", "category");
        assertThat(fields).containsEntry("content-available", 1);
    }

    /**
     * {@code Aps.getFields()} n'est pas public : le SDK Firebase le réserve à sa
     * propre sérialisation. C'est pourtant la seule lecture fidèle de ce qui
     * partira réellement — réassembler la charge à la main dans le test ne
     * testerait que le test.
     */
    private static Map<String, Object> apsFields(Aps aps) throws Exception {
        Method getFields = Aps.class.getDeclaredMethod("getFields");
        getFields.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) getFields.invoke(aps);
        return fields;
    }

    // ─── N4 — la charge de données ne doit pas faire rejeter l'envoi ──────────

    @Test
    void chargeSousLeBudget_doitPasserIntacte() {
        Map<String, Object> payload = Map.of(
            "programTitle", "Longueurs du soir",
            "activityName", "Natation",
            "addressPublic", "Piscine du Rhône, Lyon",
            "authorAvatarUrl", "https://cdn/avatars/2a19.jpg");

        Map<String, String> data = PushNotificationService.dataPayload(payload, "Titre", "Corps");

        assertThat(data).containsOnlyKeys(
            "programTitle", "activityName", "addressPublic", "authorAvatarUrl");
    }

    @Test
    void chargeTropGrosse_doitSacrifierLeDecoratif_etGarderLIdentiteDeLaSeance() {
        // APNs rejette au-delà de 4 Ko — l'utilisateur ne reçoit alors rien, et
        // rien ne le signale. Mieux vaut une carte sans avatar qu'aucune carte.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "Longueurs du soir · niveau confirmé");
        payload.put("activityName", "Natation");
        payload.put("scheduleId", UUID.randomUUID().toString());
        payload.put("authorAvatarUrl", "https://cdn/avatars/" + "x".repeat(1_500) + ".jpg");
        payload.put("addressPublic", "y".repeat(1_500));

        Map<String, String> data = PushNotificationService.dataPayload(payload, "Titre", "Corps");

        // Ce qui identifie la séance survit — programTitle est le seuil en
        // dessous duquel le client n'affiche plus rien du tout.
        assertThat(data).containsKeys("programTitle", "activityName", "scheduleId");
        assertThat(data).doesNotContainKey("authorAvatarUrl");
        assertThat(serializedSize(data)).isLessThanOrEqualTo(
            PushNotificationService.DATA_PAYLOAD_BUDGET_BYTES);
    }

    @Test
    void ordreDeSacrifice_doitCommencerParLAvatar() {
        // L'avatar a le repli le moins coûteux côté client (initiales sur
        // pastille) ; l'adresse, elle, fait disparaître une ligne entière.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "Longueurs du soir");
        // Assez gros à lui seul pour faire déborder le budget, et assez pour que
        // son retrait suffise : l'adresse doit alors survivre.
        payload.put("authorAvatarUrl", "https://cdn/avatars/" + "x".repeat(3_500) + ".jpg");
        payload.put("addressPublic", "Piscine du Rhône, 8 quai Claude Bernard, Lyon");

        Map<String, String> data = PushNotificationService.dataPayload(payload, "Titre", "Corps");

        assertThat(data).doesNotContainKey("authorAvatarUrl");
        assertThat(data).containsKey("addressPublic");
    }

    /**
     * Demande du client (réponse du 15/08, question 2) : le nom du lieu ne doit
     * jamais sauter. Il fait une trentaine de caractères et le perdre vide une
     * zone de la carte ; l'adresse en fait jusqu'à 300 et ne coûte qu'une
     * précision. La charge ici est indéracinable — même après avoir tout évincé,
     * elle déborde — et c'est justement le cas où la tentation de continuer à
     * sacrifier existe.
     */
    @Test
    void placeName_neDoitJamaisEtreSacrifie_memeSurUneChargeInderacinable() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "z".repeat(4_000));
        payload.put("placeName", "Piscine du Rhône");
        payload.put("addressPublic", "8 quai Claude Bernard, Lyon");
        payload.put("welcomeNote", "Bienvenue !");
        payload.put("authorAvatarUrl", "https://cdn/avatars/2a19.jpg");

        Map<String, String> data = PushNotificationService.dataPayload(payload, "Titre", "Corps");

        assertThat(data).containsKey("placeName");
        // Les trois évictables sont bien partis : ce n'est pas que rien n'a été
        // tenté, c'est que placeName n'est pas dans la liste.
        assertThat(data).doesNotContainKeys("authorAvatarUrl", "welcomeNote", "addressPublic");
    }

    /**
     * L'adresse part <b>après</b> la note d'accueil : le client n'affiche
     * welcomeNote nulle part, tandis que l'adresse précise encore la ligne de
     * lieu.
     */
    @Test
    void ordreDeSacrifice_laNoteDAccueilPartAvantLAdresse() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "Longueurs du soir");
        payload.put("placeName", "Piscine du Rhône");
        payload.put("welcomeNote", "w".repeat(3_500));
        payload.put("addressPublic", "8 quai Claude Bernard, Lyon");

        Map<String, String> data = PushNotificationService.dataPayload(payload, "Titre", "Corps");

        assertThat(data).doesNotContainKey("welcomeNote");
        assertThat(data).containsKeys("addressPublic", "placeName");
    }

    private static int serializedSize(Map<String, String> data) {
        return data.entrySet().stream()
            .mapToInt(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length
                + e.getValue().getBytes(StandardCharsets.UTF_8).length)
            .sum();
    }

    // ─── D6 — les heures de silence ───────────────────────────────────────────

    /**
     * Le silence est appliqué <b>ici</b>, dans le service de push, et non dans
     * {@code NotificationService} : c'est le seul endroit qui connaisse le fuseau
     * de chaque appareil. Un test d'intégration qui simule
     * {@code PushNotificationServiceInterface} remplace donc précisément le code
     * à vérifier, et ne peut rien en dire — d'où ces tests-ci.
     */
    @Test
    void pendantLeSilence_uneNotificationOrdinaire_neDoitPasPartir() throws Exception {
        UUID userId = UUID.randomUUID();
        PushNotificationService service = serviceFor(userId, quietAllDay());
        when(deviceTokenRepository.findByUserId(userId))
            .thenReturn(List.of(device("token-fr", "fr", DevicePlatform.IOS, "Europe/Paris")));

        service.sendPush(userId, NotificationType.NEW_FOLLOWER, Map.of(), 1);

        verify(firebaseMessaging, never()).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void pendantLeSilence_uneAnnulation_doitPartirQuandMeme() throws Exception {
        UUID userId = UUID.randomUUID();
        PushNotificationService service = serviceFor(userId, quietAllDay());
        when(deviceTokenRepository.findByUserId(userId))
            .thenReturn(List.of(device("token-fr", "fr", DevicePlatform.IOS, "Europe/Paris")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.SLOT_CANCELLED,
            Map.of("programTitle", "Yoga du soir"), 1);

        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void leSilence_doitSAppliquerAppareilParAppareil() throws Exception {
        // La raison d'être du fuseau porté par device_tokens : à un même instant,
        // un téléphone resté à Tokyo dort et un téléphone parisien non. Trancher
        // pour le compte entier aurait fait taire l'un ou réveillé l'autre.
        UUID userId = UUID.randomUUID();

        // La fenêtre est calculée à partir de l'heure de Tokyo MAINTENANT, et
        // non écrite en dur.
        //
        // sendPush lit Instant.now() — l'horloge fixée d'AndroidPushText ne
        // gouverne que la composition des textes. Une fenêtre écrite en dur
        // (« minuit à 8 h ») ne séparait donc les deux fuseaux que pendant sept
        // heures par jour, et ce test passait ou échouait selon l'heure à
        // laquelle on le lançait. Il a été écrit ainsi, et la suite complète l'a
        // pris en défaut le jour même.
        //
        // Une fenêtre d'une heure sur l'heure de Tokyo exclut nécessairement
        // Paris : les deux fuseaux sont séparés de sept heures.
        int tokyoHour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Tokyo")).getHour();
        PushNotificationService service = serviceFor(userId, quiet(tokyoHour, (tokyoHour + 1) % 24));
        when(deviceTokenRepository.findByUserId(userId)).thenReturn(List.of(
            device("token-paris", "fr", DevicePlatform.IOS, "Europe/Paris"),
            device("token-tokyo", "fr", DevicePlatform.IOS, "Asia/Tokyo")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.NEW_FOLLOWER, Map.of(), 1);

        // Deux fuseaux font deux groupes de texte ; un seul survit au silence —
        // celui de Paris, où il n'est pas l'heure qu'il est à Tokyo.
        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void sansHeuresDeSilence_toutDoitPartir() throws Exception {
        UUID userId = UUID.randomUUID();
        PushNotificationService service = serviceFor(userId, user(null, null));
        when(deviceTokenRepository.findByUserId(userId))
            .thenReturn(List.of(device("token-fr", "fr", DevicePlatform.IOS, "Europe/Paris")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
            .thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        service.sendPush(userId, NotificationType.NEW_FOLLOWER, Map.of(), 1);

        verify(firebaseMessaging, times(1)).sendEachForMulticast(any(MulticastMessage.class));
    }

    /**
     * Un service dont le dépôt rend cet utilisateur-là.
     *
     * <p>Le simulacre est {@code lenient} parce qu'une notification critique ne
     * le consulte jamais : {@code awake} rend la main avant, sans lire les heures
     * de silence de personne. Mockito le signalait comme inutile, ce qui est en
     * réalité la confirmation que le court-circuit fonctionne.
     */
    private PushNotificationService serviceFor(UUID userId, org.program.pair.domain.user.User user) {
        org.program.pair.repository.UserRepository users =
            org.mockito.Mockito.mock(org.program.pair.repository.UserRepository.class);
        org.mockito.Mockito.lenient().when(users.findById(userId))
            .thenReturn(java.util.Optional.of(user));
        return new PushNotificationService(firebaseMessaging, deviceTokenRepository,
            messages(), androidText(), users);
    }

    /**
     * Une fenêtre qui couvre l'heure courante quelle qu'elle soit.
     *
     * <p>Ces tests-ci appellent {@code sendPush}, qui lit {@code Instant.now()} —
     * l'horloge fixée du {@code AndroidPushText} ne porte que sur la composition
     * des textes. La fenêtre est donc calculée maintenant plutôt qu'écrite en
     * dur, sans quoi le test dirait des choses différentes selon l'heure.
     */
    private static org.program.pair.domain.user.User quietAllDay() {
        int hour = java.time.ZonedDateTime.now(ZONE).getHour();
        return user(hour, (hour + 1) % 24);
    }

    private static org.program.pair.domain.user.User quiet(int start, int end) {
        return user(start, end);
    }

    private static org.program.pair.domain.user.User user(Integer start, Integer end) {
        org.program.pair.domain.user.User user = new org.program.pair.domain.user.User();
        user.setQuietHoursStart(start == null ? null : start.shortValue());
        user.setQuietHoursEnd(end == null ? null : end.shortValue());
        return user;
    }

    private static DeviceToken device(String token, String locale) {
        return device(token, locale, DevicePlatform.IOS);
    }

    private static DeviceToken device(String token, String locale, DevicePlatform platform) {
        return device(token, locale, platform, null);
    }

    private static DeviceToken device(String token, String locale, DevicePlatform platform,
                                      String timezone) {
        return DeviceToken.builder()
            .id(UUID.randomUUID())
            .token(token)
            .platform(platform)
            .locale(locale)
            .timezone(timezone)
            .build();
    }
}
