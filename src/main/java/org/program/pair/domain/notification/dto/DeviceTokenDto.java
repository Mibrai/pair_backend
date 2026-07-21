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
    private Instant createdAt;
    private Instant lastUsedAt;

    public static DeviceTokenDto fromEntity(DeviceToken deviceToken) {
        return DeviceTokenDto.builder()
            .id(deviceToken.getId())
            .token(deviceToken.getToken())
            .platform(deviceToken.getPlatform())
            .deviceName(deviceToken.getDeviceName())
            .createdAt(deviceToken.getCreatedAt())
            .lastUsedAt(deviceToken.getLastUsedAt())
            .build();
    }
}
