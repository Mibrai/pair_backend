package org.program.pair.domain.progression;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "progressions", indexes = {
    @Index(name = "idx_progressions_program", columnList = "program_id, created_at"),
    @Index(name = "idx_progressions_user", columnList = "user_id, created_at"),
    @Index(name = "idx_progressions_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Progression {

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
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "float[]")
    private float[] metrics;

    @Column(name = "metric_labels", columnDefinition = "text[]")
    private String[] metricLabels;

    @Column(name = "is_public")
    @Builder.Default
    private Boolean isPublic = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
