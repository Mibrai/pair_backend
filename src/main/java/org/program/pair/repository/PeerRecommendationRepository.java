package org.program.pair.repository;

import org.program.pair.domain.trust.PeerRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PeerRecommendationRepository extends JpaRepository<PeerRecommendation, UUID> {

    boolean existsByFromUserIdAndToUserId(UUID fromUserId, UUID toUserId);

    List<PeerRecommendation> findByToUserIdOrderByCreatedAtDesc(UUID toUserId);

    List<PeerRecommendation> findByFromUserId(UUID fromUserId);
}
