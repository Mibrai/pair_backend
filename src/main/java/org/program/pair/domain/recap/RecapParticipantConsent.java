package org.program.pair.domain.recap;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Consentement d'une personne à apparaître nommée sur une carte.
 *
 * <p>Opt-in explicite, jamais implicite : sans ligne ici, ou avec
 * {@code showIdentity = false}, la personne est <b>comptée dans le total</b>
 * mais <b>jamais nommée ni montrée</b>. Le consentement est retirable à tout
 * moment, y compris après publication — la carte se régénère alors sans elle.
 *
 * <p>La clé est le couple (carte, personne) plutôt qu'un identifiant technique :
 * il n'y a rien à dire de plus qu'une réponse par personne et par carte, et
 * l'unicité devient une propriété du schéma plutôt qu'une règle à faire
 * respecter.
 */
@Entity
@Table(name = "recap_participant_consents")
@IdClass(RecapParticipantConsent.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecapParticipantConsent {

    @Id
    @Column(name = "recap_id", nullable = false)
    private UUID recapId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "show_identity", nullable = false)
    @Builder.Default
    private Boolean showIdentity = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (showIdentity == null) {
            this.showIdentity = false;
        }
    }

    /** Clé composite (carte, personne). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID recapId;
        private UUID userId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(recapId, other.recapId) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recapId, userId);
        }
    }
}
