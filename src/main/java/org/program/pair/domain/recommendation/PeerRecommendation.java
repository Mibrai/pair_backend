package org.program.pair.domain.recommendation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.trust.InteractionProofType;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "PeerRecommendationPhase3")
@Table(
    name = "peer_recommendations",
    uniqueConstraints = @UniqueConstraint(
        name = "unique_recommendation",
        columnNames = {"recommender_id", "recommended_id"}
    ),
    indexes = {
        @Index(name = "idx_peer_rec_to", columnList = "recommended_id"),
        @Index(name = "idx_peer_rec_from", columnList = "recommender_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeerRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recommender_id", nullable = false)
    private UUID recommenderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommender_id", insertable = false, updatable = false)
    private User recommender;

    @Column(name = "recommended_id", nullable = false)
    private UUID recommendedId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_id", insertable = false, updatable = false)
    private User recommended;

    // Nullable : une preuve d'interaction peut aussi être une présence partagée
    // confirmée sur un créneau (SHARED_ATTENDANCE), sans conversation directe.
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_proof_type", length = 20)
    private InteractionProofType interactionProofType;

    @Column(nullable = false, length = 500)
    private String comment;

    @Column(nullable = false)
    private Integer rating; // 1-5

    @Column(name = "activity_context")
    private UUID activityContext;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_context", insertable = false, updatable = false)
    private Activity activity;

    @Column(name = "program_context")
    private UUID programContext;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_context", insertable = false, updatable = false)
    private Program program;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
