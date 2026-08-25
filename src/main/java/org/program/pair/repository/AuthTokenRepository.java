package org.program.pair.repository;

import org.program.pair.domain.auth.AuthToken;
import org.program.pair.domain.auth.AuthTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenAndType(String token, AuthTokenType type);

    /**
     * Invalide les jetons encore ouverts d'un utilisateur avant d'en émettre un
     * nouveau. Sans cela, un renvoi laisserait vivre les précédents : plusieurs
     * liens actifs pour la même adresse, dont l'utilisateur ne saurait pas
     * lequel est le bon.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AuthToken t SET t.consumedAt = :maintenant
         WHERE t.user.id = :userId AND t.type = :type AND t.consumedAt IS NULL
        """)
    int consommerJetonsOuverts(@Param("userId") UUID userId,
                               @Param("type") AuthTokenType type,
                               @Param("maintenant") Instant maintenant);

    /**
     * Purge les jetons échus depuis un moment. Les jetons consommés récents sont
     * conservés volontairement : ce sont eux qui permettent de répondre « déjà
     * vérifié » à un second clic.
     */
    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.expiresAt < :seuil")
    int purgerAvant(@Param("seuil") Instant seuil);
}
