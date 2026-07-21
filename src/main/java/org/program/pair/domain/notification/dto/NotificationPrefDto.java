package org.program.pair.domain.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.NotificationFrequency;
import org.program.pair.domain.notification.NotificationPref;
import org.program.pair.domain.notification.NotificationType;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPrefDto {
    private UUID id;
    private NotificationType type;
    private Boolean emailEnabled;
    private Boolean pushEnabled;
    private NotificationFrequency frequency;

    public static NotificationPrefDto fromEntity(NotificationPref pref) {
        return NotificationPrefDto.builder()
            .id(pref.getId())
            .type(pref.getNotificationType())
            .emailEnabled(pref.getEmailEnabled())
            .pushEnabled(pref.getPushEnabled())
            .frequency(pref.getFrequency())
            .build();
    }
}
