package org.program.pair.domain.notification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.NotificationFrequency;
import org.program.pair.domain.notification.NotificationType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePreferenceRequest {

    @NotNull(message = "Type de notification requis")
    private NotificationType type;

    private Boolean emailEnabled;

    private Boolean pushEnabled;

    private NotificationFrequency frequency;
}
