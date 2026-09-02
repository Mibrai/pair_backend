package org.program.pair.domain.preference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Un réglage privé, appartenant à une seule personne.
 *
 * <p><b>La valeur est opaque, et c'est la propriété centrale.</b> Le serveur ne
 * l'interprète jamais, ne la cherche pas, ne la joint à rien et ne la sert à
 * personne d'autre que son propriétaire. Elle est écrite pour accueillir les
 * réglages qu'un client rangeait sur l'appareil et qui ne survivaient pas à une
 * réinstallation.
 *
 * <p><b>Pourquoi cette forme plutôt qu'une donnée structurée.</b> Le premier usage
 * est une liste de « proches » que le client ordonne en tête de son écran. Nous
 * aurions pu en faire une relation entre deux comptes ; nous ne l'avons pas fait,
 * et le client nous l'avait explicitement demandé. Une relation stockée devient
 * interrogeable et exportable, et un écran finit par afficher « X vous a retiré de
 * ses amis » — une notification que rien ici ne justifie. Une valeur opaque
 * appartenant à une seule personne ne peut pas devenir, par inadvertance, une
 * information sur quelqu'un d'autre : elle ne se joint à rien, et aucun écran ne
 * peut la lire à l'envers.
 */
@Entity
@Table(name = "user_preferences")
@IdClass(UserPreference.Cle.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    /** Opaque. Jamais lue par le serveur, jamais indexée, jamais servie à un tiers. */
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** La clé composite : une préférence appartient à une personne et à un nom. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cle implements Serializable {
        private UUID userId;
        private String key;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Cle autre)) {
                return false;
            }
            return java.util.Objects.equals(userId, autre.userId)
                && java.util.Objects.equals(key, autre.key);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, key);
        }
    }
}
