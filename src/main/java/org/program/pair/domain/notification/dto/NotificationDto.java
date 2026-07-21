package org.program.pair.domain.notification.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.Notification;
import org.program.pair.domain.notification.NotificationChannel;
import org.program.pair.domain.notification.NotificationType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDto {

    // Types pour lesquels le payload peut porter l'instant d'une séance à venir.
    // Aucun code applicatif ne crée encore ces notifications (V12/V13/V27 seed
    // uniquement) ; la clé "sessionAt" est la convention déjà utilisée par ce seed.
    private static final Set<NotificationType> SCHEDULE_RELATED_TYPES =
        EnumSet.of(NotificationType.PROGRAM_REMINDER, NotificationType.PROGRESSION_REMINDER);

    private UUID id;
    private NotificationType type;
    private NotificationChannel channel;
    private JsonNode payload;
    private Boolean isRead;
    private Instant sentAt;
    private Instant readAt;
    private Instant scheduledAt;

    public static NotificationDto fromEntity(Notification notification) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payloadNode = null;

        if (notification.getPayload() != null) {
            try {
                payloadNode = mapper.readTree(notification.getPayload());
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        return NotificationDto.builder()
            .id(notification.getId())
            .type(notification.getType())
            .channel(notification.getChannel())
            .payload(payloadNode)
            .isRead(notification.getIsRead())
            .sentAt(notification.getSentAt())
            .readAt(notification.getReadAt())
            .scheduledAt(extractScheduledAt(notification.getType(), payloadNode))
            .build();
    }

    /**
     * Best-effort : ne fabrique jamais scheduledAt, le laisse à null si le
     * payload ne porte pas déjà "sessionAt" en ISO-8601. Le client retombe sur
     * son heuristique textuelle dans ce cas.
     */
    private static Instant extractScheduledAt(NotificationType type, JsonNode payloadNode) {
        if (payloadNode == null || !SCHEDULE_RELATED_TYPES.contains(type)) {
            return null;
        }
        JsonNode sessionAtNode = payloadNode.get("sessionAt");
        if (sessionAtNode == null || !sessionAtNode.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(sessionAtNode.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
