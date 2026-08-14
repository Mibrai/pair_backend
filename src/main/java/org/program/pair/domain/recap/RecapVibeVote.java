package org.program.pair.domain.recap;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Un mot d'ambiance posé par une personne présente sur une carte.
 *
 * <p>Deux au maximum par personne et par créneau : au-delà, l'agrégation perd
 * son sens et la carte cesse de dire quoi que ce soit du moment.
 */
@Entity
@Table(name = "recap_vibe_votes",
    uniqueConstraints = @UniqueConstraint(name = "uq_vibe_vote", columnNames = {"recap_id", "user_id", "vibe"}),
    indexes = @Index(name = "idx_vibe_recap", columnList = "recap_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecapVibeVote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recap_id", nullable = false)
    private SlotRecap recap;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SlotVibe vibe;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
