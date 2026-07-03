package org.program.pair.domain.program;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "program_activities", indexes = {
    @Index(name = "idx_program_activities_user_program", columnList = "user_program_id"),
    @Index(name = "idx_program_activities_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_program_id", nullable = false)
    private UserProgram userProgram;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProgramActivityStatus status = ProgramActivityStatus.PENDING;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "skipped_at")
    private Instant skippedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
