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
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDto {
    private UUID id;
    private NotificationType type;
    private NotificationChannel channel;
    private JsonNode payload;
    private Boolean isRead;
    private Instant sentAt;
    private Instant readAt;

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
            .build();
    }
}
