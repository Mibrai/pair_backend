package org.program.pair.repository;

import org.program.pair.domain.trust.BadgeAward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BadgeAwardRepository extends JpaRepository<BadgeAward, BadgeAward.BadgeAwardId> {

    List<BadgeAward> findByUserId(UUID userId);

    /**
     * Charge les récompenses d'un utilisateur avec leur badge déjà résolu.
     *
     * <p>Le {@code JOIN FETCH} n'est pas une optimisation de confort :
     * {@code BadgeAward.badge} est {@code LAZY}, si bien que lire le code du
     * badge après un dérivé Spring Data déclenche une requête par badge. Comme
     * ce chargement se fait une fois par profil rendu, et qu'une page en rend
     * plusieurs dizaines, la facture est multiplicative. Un futur lecteur tenté
     * de « simplifier » vers {@code findByUserId} rouvrirait le N+1.
     */
    @Query("SELECT a FROM BadgeAward a JOIN FETCH a.badge WHERE a.user.id = :userId")
    List<BadgeAward> findByUserIdWithBadge(@Param("userId") UUID userId);

    /**
     * Les badges de <b>plusieurs</b> personnes, en une requête.
     *
     * <p>La variante ci-dessus règle le N+1 <i>par badge</i> ; celle-ci règle
     * celui <i>par profil</i>, qui est le suivant sur le chemin. Une page qui
     * rend trente-cinq cartes-souvenirs de trois hôtes payait trente-cinq
     * lectures pour trois réponses distinctes — et un aller-retour vaut ~200 ms
     * entre le service européen et la base américaine.
     */
    @Query("SELECT a FROM BadgeAward a JOIN FETCH a.badge WHERE a.user.id IN :userIds")
    List<BadgeAward> findByUserIdsWithBadge(@Param("userIds") Collection<UUID> userIds);

    Optional<BadgeAward> findByUserIdAndBadgeId(UUID userId, UUID badgeId);

    long countByUserId(UUID userId);

    @Query("SELECT COUNT(pr) FROM PeerRecommendationPhase3 pr WHERE pr.recommendedId = :userId")
    int countRecommendationsReceived(@Param("userId") UUID userId);
}
