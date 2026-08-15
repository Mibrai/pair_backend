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
    // Fuseau effectivement retenu, étiquette IANA — pas celle envoyée. Nul quand
    // le client n'en a pas envoyé, ou que l'étiquette n'a pas été reconnue : le
    // client compare son écho à ce qu'il a émis, et un écart lui signale le repli
    // sur le fuseau de référence du serveur. Même contrat que `locale`.
    private String timezone;
    private Instant createdAt;
    private Instant lastUsedAt;

    public static DeviceTokenDto fromEntity(DeviceToken deviceToken) {
        return DeviceTokenDto.builder()
            .id(deviceToken.getId())
            .token(deviceToken.getToken())
            .platform(deviceToken.getPlatform())
            .deviceName(deviceToken.getDeviceName())
            .locale(deviceToken.getLocale())
            .timezone(deviceToken.getTimezone())
            .createdAt(deviceToken.getCreatedAt())
            .lastUsedAt(deviceToken.getLastUsedAt())
            .build();
    }
}
