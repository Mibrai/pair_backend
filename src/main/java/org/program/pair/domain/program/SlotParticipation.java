package org.program.pair.domain.program;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slot_participations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "user_id"}),
    indexes = {
        @Index(name = "idx_slotpart_schedule", columnList = "schedule_id"),
        @Index(name = "idx_slotpart_user", columnList = "user_id"),
        @Index(name = "idx_slotpart_status", columnList = "status")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ParticipationStatus status = ParticipationStatus.INTERESTED;

    // Message optionnel envoyé en rejoignant ("je débute, ça vous va ?") — sanitized
    @Column(name = "join_message", length = 300)
    private String joinMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
