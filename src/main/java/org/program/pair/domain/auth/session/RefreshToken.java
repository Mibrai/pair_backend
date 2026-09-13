package org.program.pair.domain.auth.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un jeton de rafraîchissement émis, désigné par son {@code jti} (V115, P-BS-03).
 *
 * <p>{@code parentJti} relie un jeton à celui qu'on a échangé pour l'obtenir :
 * c'est la filiation qui permet la rotation tolérante de P-BS/D4 option B.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID jti;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private RefreshSession session;

    @Column(name = "parent_jti")
    private UUID parentJti;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    static RefreshToken emettre(RefreshSession session, UUID parentJti, Instant maintenant, Instant echeance) {
        RefreshToken jeton = new RefreshToken();
        jeton.jti = UUID.randomUUID();
        jeton.session = session;
        jeton.parentJti = parentJti;
        jeton.issuedAt = maintenant;
        jeton.expiresAt = echeance;
        return jeton;
    }

    boolean revoque() {
        return revokedAt != null;
    }

    boolean expire(Instant maintenant) {
        return !expiresAt.isAfter(maintenant);
    }

    void marquerUtilise(Instant maintenant) {
        this.usedAt = maintenant;
    }
}
