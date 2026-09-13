package org.program.pair.domain.auth.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un appareil connecté (V115, P-BS-03) : ce que « se déconnecter » révoque.
 *
 * <p>La session vit tant qu'elle sert : chaque échange de jeton la touche, et elle
 * n'a pas de plafond absolu (P-BS/D5 option A — le contrat publié le 10/09 dit
 * qu'une session utilisée au moins une fois par mois ne finit jamais).
 */
@Entity
@Table(name = "refresh_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    /** Toujours nulle : P-BS/D5 option A. La colonne existe pour ne pas migrer le jour où on la voudra. */
    @Column(name = "absolute_expires_at")
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 30)
    private String revokedReason;

    static RefreshSession ouvrir(UUID userId, Instant maintenant) {
        RefreshSession session = new RefreshSession();
        session.id = UUID.randomUUID();
        session.userId = userId;
        session.createdAt = maintenant;
        session.lastUsedAt = maintenant;
        return session;
    }

    boolean revoquee() {
        return revokedAt != null;
    }

    void toucher(Instant maintenant) {
        this.lastUsedAt = maintenant;
    }

    void revoquer(Instant maintenant, String motif) {
        if (revokedAt == null) {
            this.revokedAt = maintenant;
            this.revokedReason = motif;
        }
    }
}
