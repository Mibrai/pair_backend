package org.program.pair.domain.watch;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Le secret qui lève une veille : connu de la seule personne qui l'a créé.
 *
 * <p><b>Le code n'est jamais ici en clair.</b> La ligne ne porte que son empreinte
 * — {@code HMAC-SHA256(sel ‖ code)} sous le poivre, clé hors base. C'est la
 * contrainte qui rend vraie la phrase « connu de lui seul » : sans elle, un accès
 * à la base, ou quelqu'un de l'équipe, pourrait lever la veille à la place de
 * l'utilisateur. Le code est tiré, rendu une seule fois à l'arrivée, puis oublié
 * du serveur.
 *
 * <p><b>Un code court ne se protège pas par la lenteur.</b> Cinq caractères, c'est
 * un espace qu'un hachage lent ralentit sans l'empêcher ; ce qui protège, c'est le
 * poivre hors base (une fuite de la base seule ne rend rien), le plafond de trois
 * essais, et la durée de vie de quelques heures. Voir {@code Pepper}.
 *
 * <p><b>Le sel est propre à ce code</b> et vit ici, à côté de l'empreinte : c'est
 * la clé — le poivre — qui est le secret, pas le sel. Les deux empreintes (le code
 * normal et le code de contrainte) partagent le même sel et la même version de
 * clé.
 *
 * <p><b>{@code duressHash} : le code de contrainte.</b> Un second code que
 * l'utilisateur choisit et qui, présenté à la clôture, répond exactement comme un
 * succès tout en déclenchant l'escalade en silence. Nul si l'utilisateur n'en a
 * pas.
 *
 * <p><b>À la clôture, la ligne est supprimée</b>, pas marquée obsolète : un secret
 * consommé ne doit pas survivre en base.
 */
@Entity
@Table(name = "return_codes")
@EntityListeners(AuditingEntityListener.class)
public class ReturnCode {

    @Id
    @GeneratedValue
    private UUID id;

    /** La veille que ce code referme. Un pour un. */
    @Column(name = "watch_id", nullable = false, unique = true)
    private UUID watchId;

    /** Empreinte du code normal : {@code HMAC-SHA256(sel ‖ code)}, en hexadécimal. */
    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    /** Le sel propre à ce code, en base64. */
    @Column(name = "salt", nullable = false, length = 32)
    private String salt;

    /** La version de clé du poivre sous laquelle les empreintes ont été calculées. */
    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    /** Essais restants avant blocage. Trois au départ. */
    @Column(name = "attempts_left", nullable = false)
    private int attemptsLeft;

    /** Empreinte du code de contrainte, si l'utilisateur en a défini un. */
    @Column(name = "duress_hash", length = 64)
    private String duressHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReturnCode() {}

    public ReturnCode(UUID watchId, String hash, String salt, int keyVersion,
                      int attemptsLeft, String duressHash) {
        this.watchId = watchId;
        this.hash = hash;
        this.salt = salt;
        this.keyVersion = keyVersion;
        this.attemptsLeft = attemptsLeft;
        this.duressHash = duressHash;
    }

    public UUID getId() { return id; }
    public UUID getWatchId() { return watchId; }
    public String getHash() { return hash; }
    public String getSalt() { return salt; }
    public int getKeyVersion() { return keyVersion; }
    public int getAttemptsLeft() { return attemptsLeft; }
    public String getDuressHash() { return duressHash; }
    public Instant getCreatedAt() { return createdAt; }

    public void setAttemptsLeft(int attemptsLeft) { this.attemptsLeft = attemptsLeft; }
}
