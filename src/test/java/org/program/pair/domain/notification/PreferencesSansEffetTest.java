package org.program.pair.domain.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.repository.NotificationPrefRepository;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.email.EmailService;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-BL-20 et P-BA-18 (décision du 13/09) — un réglage ne fait plus taire un type
 * verrouillé, et une fréquence de résumé n'avale plus l'e-mail.
 */
@ExtendWith(MockitoExtension.class)
class PreferencesSansEffetTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationPrefRepository prefRepository;
    @Mock UserRepository userRepository;
    @Mock EmailService emailService;
    @Mock PushNotificationServiceInterface pushService;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @Mock UnreadCounter unreadCounter;
    @Mock BlockFilterService blockFilterService;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks NotificationService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void couperLaPushDeLAlerteAuContact_neDoitPasLaFaireTaire() {
        lenient().when(prefRepository.findByUserIdAndNotificationType(userId, NotificationType.WATCH_GUARDIAN_ALERT))
            .thenReturn(Optional.of(pref(NotificationType.WATCH_GUARDIAN_ALERT, false, false, NotificationFrequency.IMMEDIATE)));

        service.notify(userId, NotificationType.WATCH_GUARDIAN_ALERT, Map.of());

        verify(pushService).sendPush(eq(userId), eq(NotificationType.WATCH_GUARDIAN_ALERT), any(), anyLong());
    }

    @Test
    void unTypeVerrouille_estAccepteSansEffet_etRendLaValeurEffective() {
        NotificationPref rendue = service.updatePreference(
            userId, NotificationType.WATCH_RETURN_REMINDER, false, false, NotificationFrequency.IMMEDIATE);

        assertThat(rendue.getPushEnabled()).isTrue();
        assertThat(rendue.getEmailEnabled()).isTrue();
        verify(prefRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("deprecation")
    void choisirLeResumeQuotidien_neDoitPasSupprimerLEmailDAnnulation() {
        when(prefRepository.findByUserIdAndNotificationType(userId, NotificationType.SLOT_CANCELLED))
            .thenReturn(Optional.of(pref(NotificationType.SLOT_CANCELLED, true, true, NotificationFrequency.DAILY_DIGEST)));

        service.notify(userId, NotificationType.SLOT_CANCELLED, Map.of());

        verify(emailService).sendNotificationEmail(eq(userId), eq(NotificationType.SLOT_CANCELLED), any());
    }

    @Test
    @SuppressWarnings("deprecation")
    void uneFrequenceDeResume_sEnregistreCommeImmediate() {
        when(prefRepository.findByUserIdAndNotificationType(userId, NotificationType.AUTHOR_NEW_PROGRAM))
            .thenReturn(Optional.empty());
        when(prefRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationPref hebdo = service.updatePreference(
            userId, NotificationType.AUTHOR_NEW_PROGRAM, null, null, NotificationFrequency.WEEKLY);

        assertThat(hebdo.getFrequency()).isEqualTo(NotificationFrequency.IMMEDIATE);
    }

    private NotificationPref pref(NotificationType type, boolean email, boolean push, NotificationFrequency frequence) {
        return NotificationPref.builder()
            .notificationType(type)
            .emailEnabled(email)
            .pushEnabled(push)
            .frequency(frequence)
            .build();
    }
}
