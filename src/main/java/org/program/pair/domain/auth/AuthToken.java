package org.program.pair.domain.auth;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Jeton à usage unique envoyé par e-mail (vérification d'adresse, ou
 * réinitialisation de mot de passe).
 *
 * <p>Ces jetons vivaient dans des {@code ConcurrentHashMap} d'instance : ils
 * disparaissaient à chaque redéploiement, et le lien reçu par l'utilisateur
 * devenait « invalide » sans que rien ne distingue ce cas d'un vrai faux jeton.
 * Voir V79 pour le raisonnement complet.
 */
@Entity
@Table(name = "auth_tokens", indexes = {
    @Index(name = "idx_auth_tokens_user_type", columnList = "user_id, type"),
    @Index(name = "idx_auth_tokens_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuthTokenType type;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Renseigné à la première utilisation. Un jeton consommé reste en base
     * exprès : c'est ce qui permet de répondre « compte déjà vérifié » plutôt
     * que « lien inconnu » à qui clique deux fois — le second message ferait
     * croire à une panne alors que tout a fonctionné.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void horodater() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean estExpire() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean estConsomme() {
        return consumedAt != null;
    }
}
