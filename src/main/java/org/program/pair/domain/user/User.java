package org.program.pair.domain.user;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.locationtech.jts.geom.Point;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_last_active", columnList = "last_active_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    @Email
    @NotBlank
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    @NotBlank
    private String passwordHash;

    @Column(length = 20)
    private String phone;

    @Column(name = "display_name", nullable = false, length = 80)
    @NotBlank
    @Size(max = 80)
    private String displayName;

    @Column(length = 1000)
    @Size(max = 1000)
    private String bio;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "blur_radius_m", nullable = false)
    @Builder.Default
    private Integer blurRadiusM = 500;

    @Column(name = "location_public", nullable = false)
    @Builder.Default
    private Boolean locationPublic = false;

    @Column(name = "online_status_visible", nullable = false)
    @Builder.Default
    private Boolean onlineStatusVisible = false;

    @Column(name = "receive_messages", nullable = false)
    @Builder.Default
    private Boolean receiveMessages = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Embedded
    @Builder.Default
    private PrivacySettings privacySettings = new PrivacySettings();

    // Métriques de valeur meetDo : régularité et diversité des partenaires,
    // jamais un score comparatif entre personnes (voir PracticeStatsService).
    @Column(name = "distinct_partners_count", nullable = false)
    @Builder.Default
    private Integer distinctPartnersCount = 0;

    @Column(name = "attendance_count", nullable = false)
    @Builder.Default
    private Integer attendanceCount = 0;

    @Column(name = "current_streak_weeks", nullable = false)
    @Builder.Default
    private Integer currentStreakWeeks = 0;

    @Column(name = "last_attendance_at")
    private Instant lastAttendanceAt;

    // Parcours d'accueil (V60). Placés en fin de classe à dessein : plusieurs
    // champs de cette entité portent leur annotation d'audit sur la ligne qui
    // les précède, et s'insérer entre les deux la déplacerait silencieusement.
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_step", length = 30)
    private OnboardingStep onboardingStep;
}
