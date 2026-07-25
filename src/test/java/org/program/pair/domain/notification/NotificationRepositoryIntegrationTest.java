package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit le bug d'écriture documenté : la colonne notifications.payload
 * est jsonb, mais Notification.payload (String) sans indication de type JDBC
 * est liée comme varchar par Hibernate, provoquant
 * "column is jsonb but expression is of type character varying" à l'insert.
 * C'est cette même écriture que NotificationService.notify() effectue de
 * façon asynchrone (silencieusement avalée par le gestionnaire d'exceptions
 * async par défaut) pour toute notification avec payload.
 */
class NotificationRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;

    @Test
    void save_devraitPersisterUnPayloadJson_sansErreurDeTypeJdbc() {
        var user = userRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .orElseThrow();

        Notification notification = Notification.builder()
            .user(user)
            .type(NotificationType.SLOT_JOINED)
            .channel(NotificationChannel.IN_APP)
            .payload("{\"scheduleId\":\"11111111-1111-1111-1111-111111111111\"}")
            .isRead(false)
            .build();

        Notification saved = notificationRepository.save(notification);
        notificationRepository.flush();

        assertThat(saved.getId()).isNotNull();

        Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).contains("scheduleId");
    }
}
