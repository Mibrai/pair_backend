package org.program.pair.domain.program;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "program_enrollments",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "program_id"}),
    indexes = {
        @Index(name = "idx_enrollments_user", columnList = "user_id"),
        @Index(name = "idx_enrollments_program", columnList = "program_id"),
        @Index(name = "idx_enrollments_status", columnList = "status"),
        @Index(name = "idx_enrollments_enrolled_at", columnList = "enrolled_at")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EnrollmentStatus status = EnrollmentStatus.ACTIVE;

    @CreatedDate
    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "left_reason", length = 500)
    @Size(max = 500)
    private String leftReason;
}
