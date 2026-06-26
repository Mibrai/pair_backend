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

    Optional<BadgeAward> findByUserIdAndBadgeId(UUID userId, UUID badgeId);

    long countByUserId(UUID userId);

    @Query("SELECT COUNT(pr) FROM PeerRecommendationPhase3 pr WHERE pr.recommendedId = :userId")
    int countRecommendationsReceived(@Param("userId") UUID userId);
}
