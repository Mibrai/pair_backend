package org.program.pair.domain.notification.dto;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.notification.Notification;
import org.program.pair.domain.notification.NotificationChannel;
import org.program.pair.domain.notification.NotificationType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre l'ajout du champ additif scheduledAt : dérivé du payload existant
 * pour les rappels de séance, jamais fabriqué, null quand la donnée
 * sous-jacente est absente ou non pertinente pour le type.
 */
class NotificationDtoTest {

    private Notification notification(NotificationType type, String payload) {
        return Notification.builder()
            .id(UUID.randomUUID())
            .type(type)
            .channel(NotificationChannel.IN_APP)
            .payload(payload)
            .isRead(false)
            .sentAt(Instant.now())
            .build();
    }

    @Test
    void scheduledAt_devraitEtreExtrait_pourUnRappelDeSeanceAvecSessionAt() {
        Notification notif = notification(
            NotificationType.PROGRAM_REMINDER,
            "{\"programId\":\"40000000-0000-0000-0000-000000000001\",\"sessionAt\":\"2026-07-13T08:00:00Z\"}"
        );

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isEqualTo(Instant.parse("2026-07-13T08:00:00Z"));
    }

    @Test
    void scheduledAt_devraitEtreExtrait_pourUnRappelDeProgression() {
        Notification notif = notification(
            NotificationType.PROGRESSION_REMINDER,
            "{\"streak\":5,\"sessionAt\":\"2026-08-01T06:30:00Z\"}"
        );

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isEqualTo(Instant.parse("2026-08-01T06:30:00Z"));
    }

    @Test
    void scheduledAt_devraitEtreNull_quandLePayloadNaPasSessionAt() {
        Notification notif = notification(
            NotificationType.PROGRAM_REMINDER,
            "{\"programId\":\"40000000-0000-0000-0000-000000000001\"}"
        );

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isNull();
    }

    @Test
    void scheduledAt_devraitEtreNull_pourUnTypeNonPertinent() {
        Notification notif = notification(
            NotificationType.NEW_MESSAGE,
            "{\"sessionAt\":\"2026-07-13T08:00:00Z\"}"
        );

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isNull();
    }

    @Test
    void scheduledAt_devraitEtreNull_quandSessionAtNestPasParsable() {
        Notification notif = notification(
            NotificationType.PROGRAM_REMINDER,
            "{\"sessionAt\":\"dans 1 jour\"}"
        );

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isNull();
    }

    @Test
    void scheduledAt_devraitEtreNull_quandPasDePayload() {
        Notification notif = notification(NotificationType.PROGRAM_REMINDER, null);

        NotificationDto dto = NotificationDto.fromEntity(notif);

        assertThat(dto.getScheduledAt()).isNull();
    }
}
