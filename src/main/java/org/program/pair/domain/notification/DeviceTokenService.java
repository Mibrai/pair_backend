package org.program.pair.domain.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    /**
     * Enregistrer ou mettre à jour un device token
     */
    public DeviceToken registerToken(UUID userId, String token, DevicePlatform platform, String deviceName) {
        // Vérifier si le token existe déjà
        if (deviceTokenRepository.existsByUserIdAndToken(userId, token)) {
            DeviceToken existing = deviceTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalStateException("Token should exist"));
            existing.setLastUsedAt(Instant.now());
            if (deviceName != null) {
                existing.setDeviceName(deviceName);
            }
            return deviceTokenRepository.save(existing);
        }

        // Créer nouveau token
        DeviceToken deviceToken = DeviceToken.builder()
            .user(userRepository.getReferenceById(userId))
            .token(token)
            .platform(platform)
            .deviceName(deviceName)
            .createdAt(Instant.now())
            .lastUsedAt(Instant.now())
            .build();

        DeviceToken saved = deviceTokenRepository.save(deviceToken);
        log.info("Device token registered for user {} on platform {}", userId, platform);
        return saved;
    }

    /**
     * Supprimer un device token
     */
    public void unregisterToken(String token) {
        deviceTokenRepository.deleteByToken(token);
        log.info("Device token unregistered: {}", token.substring(0, Math.min(10, token.length())) + "...");
    }

    /**
     * Récupérer les tokens d'un utilisateur
     */
    @Transactional(readOnly = true)
    public List<DeviceToken> getUserTokens(UUID userId) {
        return deviceTokenRepository.findByUserId(userId);
    }

    /**
     * Supprimer tous les tokens d'un utilisateur
     */
    public void unregisterAllUserTokens(UUID userId) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        deviceTokenRepository.deleteAll(tokens);
        log.info("All device tokens unregistered for user {}", userId);
    }
}
