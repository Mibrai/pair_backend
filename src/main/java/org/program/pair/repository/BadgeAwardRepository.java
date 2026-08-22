package org.program.pair.repository;

import org.program.pair.domain.trust.BadgeAward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    Optional<BadgeAward> findByUserIdAndBadgeId(UUID userId, UUID badgeId);

    long countByUserId(UUID userId);

    @Query("SELECT COUNT(pr) FROM PeerRecommendationPhase3 pr WHERE pr.recommendedId = :userId")
    int countRecommendationsReceived(@Param("userId") UUID userId);
}
