package org.program.pair.domain.notification;

import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.config.LocaleConfig;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private PushNotificationService service() {
        // Le vrai MessageSource de l'application (messages*.properties du
        // classpath), pas un stub : le test vérifie aussi que les clés existent.
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(LocaleConfig.FRENCH);
        return new PushNotificationService(firebaseMessaging, deviceTokenRepository,
            new Messages(source));
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

    private static int serializedSize(Map<String, String> data) {
        return data.entrySet().stream()
            .mapToInt(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length
                + e.getValue().getBytes(StandardCharsets.UTF_8).length)
            .sum();
    }

    private static DeviceToken device(String token, String locale) {
        return DeviceToken.builder()
            .id(UUID.randomUUID())
            .token(token)
            .platform(DevicePlatform.IOS)
            .locale(locale)
            .build();
    }
}
