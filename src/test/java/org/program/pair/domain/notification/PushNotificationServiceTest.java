package org.program.pair.domain.notification;

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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    private static DeviceToken device(String token, String locale) {
        return DeviceToken.builder()
            .id(UUID.randomUUID())
            .token(token)
            .platform(DevicePlatform.IOS)
            .locale(locale)
            .build();
    }
}
