package org.program.pair.domain.program;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_programs", indexes = {
    @Index(name = "idx_user_programs_user", columnList = "user_id"),
    @Index(name = "idx_user_programs_program", columnList = "program_id"),
    @Index(name = "idx_user_programs_schedule", columnList = "schedule_id"),
    @Index(name = "idx_user_programs_status", columnList = "status"),
    @Index(name = "idx_user_programs_joined", columnList = "joined_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserProgramStatus status = UserProgramStatus.ACTIVE;

    @Column(name = "leave_reason", columnDefinition = "TEXT")
    private String leaveReason;

    @Column(name = "progress_percentage", nullable = false)
    @Builder.Default
    private Integer progressPercentage = 0;

    @Column(name = "activities_completed", nullable = false)
    @Builder.Default
    private Integer activitiesCompleted = 0;

    @Column(name = "activities_skipped", nullable = false)
    @Builder.Default
    private Integer activitiesSkipped = 0;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;
}
