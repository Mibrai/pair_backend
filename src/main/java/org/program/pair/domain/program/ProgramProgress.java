package org.program.pair.domain.program;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.activity.Activity;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "program_progress",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_program_id", "activity_id"}),
    indexes = {
        @Index(name = "idx_progress_user_program", columnList = "user_program_id"),
        @Index(name = "idx_progress_activity", columnList = "activity_id"),
        @Index(name = "idx_progress_completed_at", columnList = "completed_at")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_program_id", nullable = false)
    private ProgramEnrollment userProgram;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean skipped = false;

    @Column(columnDefinition = "TEXT")
    @Size(max = 1000)
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
