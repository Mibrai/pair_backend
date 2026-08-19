package org.program.pair.domain.language;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Une langue déclarée par quelqu'un.
 *
 * <p>Clé composite {@code (user_id, language)} plutôt qu'un identifiant propre :
 * déclarer deux fois la même langue n'a aucun sens, et la base est le seul
 * endroit où cette évidence tient toute seule.
 */
@Entity
@Table(name = "user_languages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLanguage {

    @EmbeddedId
    private Id id;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency", nullable = false, length = 20)
    private LanguageProficiency proficiency;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "language", nullable = false, length = 5)
        private String language;
    }
}
