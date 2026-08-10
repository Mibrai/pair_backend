package org.program.pair.domain.notification;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_tokens", indexes = {
    @Index(name = "idx_device_tokens_user", columnList = "user_id"),
    @Index(name = "idx_device_tokens_token", columnList = "token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 500)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    // Langue des textes push envoyés à CET appareil ("fr", "en", "de").
    // Nulle pour un appareil enregistré sans langue connue : repli français,
    // comme un Accept-Language absent. Voir V49 pour la justification.
    @Column(length = 10)
    private String locale;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at", nullable = false)
    @Builder.Default
    private Instant lastUsedAt = Instant.now();
}
