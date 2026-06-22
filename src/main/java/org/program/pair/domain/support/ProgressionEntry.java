package org.program.pair.domain.support;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "progression_entries", indexes = {
    @Index(name = "idx_progression_program", columnList = "program_id"),
    @Index(name = "idx_progression_user", columnList = "user_id"),
    @Index(name = "idx_progression_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgressionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 150)
    @Size(max = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    @Size(max = 2000)
    private String content;

    @Column(columnDefinition = "float[]")
    private float[] metrics;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
