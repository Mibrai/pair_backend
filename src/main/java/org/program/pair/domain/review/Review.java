package org.program.pair.domain.review;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.trust.InteractionProofType;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "ReviewPhase3")
@Table(
    name = "reviews",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_review_program_reviewer",
        columnNames = {"program_id", "reviewer_id"}
    )
)
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @ToString.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nul une fois le compte de l'auteur purgé (V123, P-BL-03) : l'avis reste, anonymisé. */
    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", insertable = false, updatable = false)
    private User reviewer;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", insertable = false, updatable = false)
    private Program program;

    // Optionnel : une review ne requiert pas de preuve d'interaction stricte,
    // contrairement à une recommandation (voir PeerRecommendation).
    @Column(name = "interaction_proof_id")
    private UUID interactionProofId;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_proof_type", length = 20)
    private InteractionProofType interactionProofType;

    @Column(name = "score", nullable = false)
    private Float score;

    @Column(length = 1000)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Identité seule, et stable avant comme après {@code persist} (P-BA-12).
     * L'{@code equals} de {@code @Data} comparait tous les champs, associations
     * paresseuses comprises : une comparaison pouvait charger la base, ou boucler.
     */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Review autre && id != null && id.equals(autre.id));
    }

    @Override
    public int hashCode() {
        return Review.class.hashCode();
    }
}
