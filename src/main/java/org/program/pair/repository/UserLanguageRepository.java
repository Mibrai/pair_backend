package org.program.pair.repository;

import org.program.pair.domain.language.UserLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserLanguageRepository extends JpaRepository<UserLanguage, UserLanguage.Id> {

    @Query("SELECT ul FROM UserLanguage ul WHERE ul.id.userId = :userId ORDER BY ul.id.language")
    List<UserLanguage> findByUserId(@Param("userId") UUID userId);

    @Query("DELETE FROM UserLanguage ul WHERE ul.id.userId = :userId")
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByUserId(@Param("userId") UUID userId);

    /**
     * Qui, parmi ces personnes, parle au moins une des langues demandées.
     *
     * <p>Rend des identifiants et non des entités : l'appelant filtre une liste
     * déjà en mémoire, et charger les lignes complètes pour n'en lire que la clé
     * serait du travail pour rien.
     */
    @Query("""
        SELECT DISTINCT ul.id.userId FROM UserLanguage ul
        WHERE ul.id.userId IN :userIds AND ul.id.language IN :languages
        """)
    List<UUID> findUserIdsSpeaking(@Param("userIds") Collection<UUID> userIds,
                                   @Param("languages") Collection<String> languages);
}
