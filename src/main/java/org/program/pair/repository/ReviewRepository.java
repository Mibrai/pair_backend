package org.program.pair.repository;

import org.program.pair.domain.review.Review;
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
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /**
     * Trouve tous les avis d'un programme
     */
    Page<Review> findByProgramIdOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    /**
     * Trouve tous les avis donnés par un utilisateur
     */
    Page<Review> findByReviewerIdOrderByCreatedAtDesc(UUID reviewerId, Pageable pageable);

    /**
     * Vérifie si un avis existe déjà
     */
    Optional<Review> findByReviewerIdAndProgramId(UUID reviewerId, UUID programId);

    /**
     * Compte les avis d'un programme
     */
    long countByProgramId(UUID programId);

    /**
     * Compte les avis donnés par un utilisateur
     */
    long countByReviewerId(UUID reviewerId);

    /**
     * Calcule la note moyenne d'un programme
     */
    @Query("SELECT AVG(r.overallRating) FROM ReviewPhase3 r WHERE r.programId = :programId")
    Double findAverageRatingByProgramId(@Param("programId") UUID programId);

    /**
     * Trouve les meilleurs programmes (rating >= 4)
     */
    @Query("""
        SELECT r.programId, AVG(r.overallRating) as avgRating, COUNT(r) as reviewCount
        FROM ReviewPhase3 r
        GROUP BY r.programId
        HAVING AVG(r.overallRating) >= 4 AND COUNT(r) >= 3
        ORDER BY AVG(r.overallRating) DESC, COUNT(r) DESC
        """)
    List<Object[]> findTopRatedPrograms(Pageable pageable);

    /**
     * Find reviews by reviewer (for GDPR export)
     */
    List<Review> findByReviewerId(UUID reviewerId);

    /**
     * Anonymize reviews for GDPR purge (Article 17)
     */
    @Modifying
    @Query("UPDATE Review r SET r.reviewer = null, r.comment = '[Avis anonymisé]' WHERE r.reviewer.id = :reviewerId")
    void anonymizeByReviewerId(@Param("reviewerId") UUID reviewerId);
}
