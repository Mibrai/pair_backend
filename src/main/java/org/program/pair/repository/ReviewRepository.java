package org.program.pair.repository;

import org.program.pair.domain.review.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByProgramIdOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    Page<Review> findByReviewerIdOrderByCreatedAtDesc(UUID reviewerId, Pageable pageable);

    Optional<Review> findByReviewerIdAndProgramId(UUID reviewerId, UUID programId);

    long countByProgramId(UUID programId);

    long countByReviewerId(UUID reviewerId);

    @Query("SELECT AVG(r.score) FROM ReviewPhase3 r WHERE r.programId = :programId")
    Double findAverageRatingByProgramId(@Param("programId") UUID programId);

    /**
     * Moyenne et nombre d'avis de plusieurs programmes, une ligne par programme
     * — {@code [UUID programId, Double moyenne, Long nombre]}.
     *
     * <p>Remplace, sur les chemins qui rendent une liste, deux requêtes par
     * programme : la moyenne et le compte. Cent programmes affichés en
     * demandaient deux cents.
     *
     * <p><b>Un programme sans aucun avis n'a pas de ligne</b> : le
     * {@code GROUP BY} n'en produit que pour ce qui existe. L'appelant doit lire
     * l'absence comme « aucune moyenne » — ce que rendait déjà
     * {@link #findAverageRatingByProgramId} — et comme un compte de zéro, et non
     * comme un compte nul.
     */
    @Query("""
        SELECT r.programId, AVG(r.score), COUNT(r)
        FROM ReviewPhase3 r
        WHERE r.programId IN :programIds
        GROUP BY r.programId
        """)
    List<Object[]> findRatingSummariesByProgramIds(@Param("programIds") Collection<UUID> programIds);

    @Query("""
        SELECT r.programId, AVG(r.score) as avgRating, COUNT(r) as reviewCount
        FROM ReviewPhase3 r
        GROUP BY r.programId
        HAVING AVG(r.score) >= 4 AND COUNT(r) >= 3
        ORDER BY AVG(r.score) DESC, COUNT(r) DESC
        """)
    List<Object[]> findTopRatedPrograms(Pageable pageable);

    List<Review> findByReviewerId(UUID reviewerId);

    @Modifying
    @Query("UPDATE ReviewPhase3 r SET r.reviewer = null, r.comment = '[Avis anonymisé]' WHERE r.reviewer.id = :reviewerId")
    void anonymizeByReviewerId(@Param("reviewerId") UUID reviewerId);
}
