package org.program.pair.repository;

import org.program.pair.domain.recommendation.PeerRecommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PeerRecommendationRepository extends JpaRepository<PeerRecommendation, UUID> {

    /**
     * Trouve toutes les recommandations reçues par un utilisateur
     */
    Page<PeerRecommendation> findByRecommendedIdOrderByCreatedAtDesc(UUID recommendedId, Pageable pageable);

    /**
     * Trouve toutes les recommandations données par un utilisateur
     */
    Page<PeerRecommendation> findByRecommenderIdOrderByCreatedAtDesc(UUID recommenderId, Pageable pageable);

    /**
     * Vérifie si une recommandation existe déjà
     */
    Optional<PeerRecommendation> findByRecommenderIdAndRecommendedId(UUID recommenderId, UUID recommendedId);

    /**
     * Compte les recommandations reçues par un utilisateur
     */
    long countByRecommendedId(UUID recommendedId);

    /**
     * Compte les recommandations données par un utilisateur
     */
    long countByRecommenderId(UUID recommenderId);

    /**
     * Calcule la note moyenne d'un utilisateur
     */
    @Query("SELECT AVG(r.rating) FROM PeerRecommendationPhase3 r WHERE r.recommendedId = :userId")
    Double findAverageRatingByUserId(@Param("userId") UUID userId);

    /**
     * Trouve les recommandations par contexte d'activité
     */
    List<PeerRecommendation> findByActivityContext(UUID activityId);

    /**
     * Trouve les recommandations par contexte de programme
     */
    List<PeerRecommendation> findByProgramContext(UUID programId);

    /**
     * Find recommendations by recommender (for GDPR export)
     */
    List<PeerRecommendation> findByRecommenderId(UUID recommenderId);

    /**
     * Anonymize recommendations for GDPR purge (Article 17)
     */
    @Modifying
    @Query("UPDATE PeerRecommendation r SET r.recommender = null, r.comment = '[Recommandation anonymisée]' WHERE r.recommender.id = :recommenderId")
    void anonymizeByRecommenderId(@Param("recommenderId") UUID recommenderId);
}
