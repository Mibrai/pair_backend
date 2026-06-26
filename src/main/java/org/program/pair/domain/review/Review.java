package org.program.pair.domain.review;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity(name = "ReviewPhase3")
@Table(
    name = "reviews",
    uniqueConstraints = @UniqueConstraint(
        name = "unique_review",
        columnNames = {"reviewer_id", "program_id"}
    )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", insertable = false, updatable = false)
    private User reviewer;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", insertable = false, updatable = false)
    private Program program;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "overall_rating", nullable = false)
    private Integer overallRating; // 1-5

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria_scores", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> criteriaScores;
    // Map keys: ORGANIZATION, COMMUNICATION, ATMOSPHERE, DIFFICULTY, RECOMMENDATION
    // Map values: 1-5

    @Column(nullable = false, length = 1000)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
