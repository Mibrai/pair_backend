package org.program.pair.domain.auth;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.time.Instant;
import java.util.HexFormat;
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

    /**
     * Le jeton en clair, <b>en sursis</b>.
     *
     * <p>C'est le défaut que P-BS-09 ferme : une lecture de cette table donnait
     * tous les liens de vérification et de réinitialisation en circulation, donc
     * tous les comptes correspondants — sans mot de passe, et sans laisser de
     * trace dans l'application. La recherche passe désormais par
     * {@link #tokenHash} et plus une seule lecture du code ne compare cette
     * colonne.
     *
     * <p><b>Elle reste écrite jusqu'au Lot 4</b>, et c'est le seul but de sa
     * survie : tant qu'elle porte la valeur, un retour arrière du code — qui ne
     * connaîtrait pas l'empreinte — retrouve encore les jetons émis pendant la
     * fenêtre. Le retrait est une opération du Lot 4, au moins sept jours après
     * la livraison, soit bien au-delà des 24 h de validité maximale : un commit
     * qui cesse de l'écrire, puis une migration qui la supprime.
     *
     * <p>{@code nullable} n'est plus déclaré ici : V112 a retiré le
     * {@code NOT NULL} de la colonne pour que le Lot 4 n'ait qu'à la supprimer,
     * et pour qu'un retour arrière ne se heurte à aucune contrainte.
     */
    @Column(unique = true, length = 255)
    private String token;

    /**
     * L'empreinte SHA-256 du jeton, en hexadécimal minuscule — 64 caractères.
     *
     * <p><b>C'est par elle que l'on retrouve un jeton</b>, et c'est tout le
     * changement : la valeur présentée par le navigateur n'est jamais comparée à
     * une valeur stockée, elle est condensée puis comparée à un condensat.
     * Personne ne peut donc reconstituer un lien à partir de la table.
     *
     * <p><b>Pas de sel, et pas de bcrypt.</b> Un jeton est un UUID v4 tiré au
     * hasard — 122 bits — et non un mot de passe : il n'existe aucun
     * dictionnaire à éprouver, et un SHA-256 nu suffit. Un condensat lent serait
     * ici un coût par clic sans gain, et un sel par ligne empêcherait la
     * recherche par empreinte, qui est précisément ce qu'on vient acheter.
     *
     * <p>{@code nullable = false} est une assertion sur <b>notre</b> code, pas le
     * reflet exact de la colonne : V112 la laisse acceptant {@code NULL} le temps
     * de la fenêtre de retour arrière (une version antérieure du code
     * n'écrirait que {@code token}), tandis qu'{@link #empreinte} garantit que ce
     * service n'insère jamais de ligne sans empreinte. Le {@code NOT NULL}
     * rejoindra la colonne au Lot 4, quand {@code token} disparaîtra.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

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

    /**
     * L'empreinte d'un jeton : SHA-256, hexadécimal minuscule, 64 caractères.
     *
     * <p><b>Elle doit rendre exactement ce que la base calcule</b>, sans quoi les
     * jetons rattrapés par V112 seraient introuvables. Le rattrapage de la
     * migration écrit {@code encode(sha256(convert_to(token, 'UTF8')), 'hex')} :
     * d'où {@code UTF-8} ici et non le jeu de caractères par défaut de la
     * plate-forme, et d'où l'hexadécimal en minuscules, qui est la forme rendue
     * par {@code encode(…, 'hex')}.
     *
     * <p>Ici et non dans un utilitaire de {@code shared} : c'est cette entité qui
     * décide comment elle se retrouve, et l'empreinte n'a pas d'autre usage dans
     * l'application.
     */
    public static String empreinte(String jetonEnClair) {
        if (jetonEnClair == null) {
            throw new IllegalArgumentException("Un jeton nul n'a pas d'empreinte");
        }
        try {
            byte[] condensat = MessageDigest.getInstance("SHA-256")
                .digest(jetonEnClair.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(condensat);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 est exigé de toute implémentation de la plate-forme : cette
            // branche décrit une JVM cassée, pas une donnée refusée.
            //
            // ProviderException et non IllegalStateException, qui serait le
            // premier réflexe : ArchitectureTest interdit de construire une
            // IllegalStateException depuis ..domain.. — le gestionnaire qui la
            // rendait en 409 a été retiré (P-BA-10), et quatre classes seulement
            // sont exemptées, nommément. Celle-ci porte la même intention sans
            // demander une cinquième exemption, et elle rend 500, ce qui est juste.
            throw new ProviderException("SHA-256 indisponible", impossible);
        }
    }

    public boolean estExpire() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean estConsomme() {
        return consumedAt != null;
    }
}
