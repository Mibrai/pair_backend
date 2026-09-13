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

    /**
     * Retrouve un jeton par l'<b>empreinte</b> de la valeur présentée.
     *
     * <p>C'est la seule recherche du chemin critique depuis P-BS-09 : la base ne
     * garde plus de valeur comparable à ce que porte le lien, et une lecture de
     * la table ne rend plus aucun lien utilisable.
     *
     * <p>L'appelant condense par {@link AuthToken#empreinte(String)}. Le
     * rattrapage de V112 a calculé la même empreinte pour toutes les lignes
     * existantes, y compris les jetons déjà envoyés : c'est ce qui fait qu'aucun
     * lien en circulation ne cesse de fonctionner au déploiement.
     */
    Optional<AuthToken> findByTokenHashAndType(String tokenHash, AuthTokenType type);

    /**
     * La recherche par valeur en clair, <b>conservée jusqu'au Lot 4 et par aucun
     * chemin de production</b>.
     *
     * <p>Elle ne sert plus qu'aux tests qui se donnent le jeton qu'ils viennent
     * d'émettre. Aucun appelant applicatif ne doit la reprendre : la remettre
     * dans un chemin de production rouvrirait exactement ce que P-BS-09 ferme —
     * une valeur de la table suffirait à nouveau à prendre un compte. Elle
     * disparaît avec la colonne, au Lot 4.
     */
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
