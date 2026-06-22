package org.program.pair.domain.notification;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.util.UUID;

@Entity
@Table(name = "notification_prefs",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "notification_type"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPref {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private NotificationType notificationType;

    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private Boolean emailEnabled = true;

    @Column(name = "push_enabled", nullable = false)
    @Builder.Default
    private Boolean pushEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NotificationFrequency frequency = NotificationFrequency.IMMEDIATE;
}
