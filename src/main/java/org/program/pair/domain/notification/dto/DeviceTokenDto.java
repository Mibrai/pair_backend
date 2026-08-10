package org.program.pair.domain.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.DevicePlatform;
import org.program.pair.domain.notification.DeviceToken;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTokenDto {
    private UUID id;
    private String token;
    private DevicePlatform platform;
    private String deviceName;
    // Langue effectivement retenue pour cet appareil ("fr", "en", "de"), après
    // normalisation — pas l'étiquette envoyée. Nulle = repli français.
    private String locale;
    private Instant createdAt;
    private Instant lastUsedAt;

    public static DeviceTokenDto fromEntity(DeviceToken deviceToken) {
        return DeviceTokenDto.builder()
            .id(deviceToken.getId())
            .token(deviceToken.getToken())
            .platform(deviceToken.getPlatform())
            .deviceName(deviceToken.getDeviceName())
            .locale(deviceToken.getLocale())
            .createdAt(deviceToken.getCreatedAt())
            .lastUsedAt(deviceToken.getLastUsedAt())
            .build();
    }
}
