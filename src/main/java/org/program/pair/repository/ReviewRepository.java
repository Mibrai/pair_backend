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

    Page<Review> findByProgramIdOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    Page<Review> findByReviewerIdOrderByCreatedAtDesc(UUID reviewerId, Pageable pageable);

    /** L'avis d'un auteur sur un programme, en page : ce que lit un non-organisateur (P-BL-10). */
    Page<Review> findByProgramIdAndReviewerIdOrderByCreatedAtDesc(
        UUID programId, UUID reviewerId, Pageable pageable);

    long countByProgramIdAndReviewerId(UUID programId, UUID reviewerId);

    /**
     * « Recommandé par des participants » (demande mobile avis du 15/09) : au moins
     * trois personnes distinctes qui ont une présence confirmée sur une séance du
     * programme ET l'ont recommandé. L'organisateur ne compte pas, ni un compte
     * fermé, ni une personne bloquée avec l'organisateur ou avec le lecteur.
     *
     * <p>Rend un booléen, et c'est tout le contrat : le décompte ne sort pas de la
     * base. Sur la vie du programme, sans fenêtre glissante. Aucun commentaire SQL
     * dans le corps : une apostrophe y casse l'analyse des paramètres.
     */
    @Query(value = """
        SELECT COUNT(DISTINCT r.reviewer_id) >= 3
        FROM reviews r
        JOIN users u ON u.id = r.reviewer_id
        JOIN programs p ON p.id = r.program_id
        JOIN user_activities ua ON ua.id = p.user_activity_id
        WHERE r.program_id = :programId
          AND r.recommend = TRUE
          AND r.reviewer_id <> ua.user_id
          AND u.is_active = TRUE
          AND EXISTS (
              SELECT 1 FROM attendances a
              JOIN schedules s ON s.id = a.schedule_id
              WHERE s.program_id = r.program_id
                AND a.user_id = r.reviewer_id
                AND a.was_present = TRUE)
          AND NOT EXISTS (
              SELECT 1 FROM user_blocks ub
              WHERE (ub.blocker_id = ua.user_id AND ub.blocked_id = u.id)
                 OR (ub.blocker_id = u.id AND ub.blocked_id = ua.user_id))
          AND (CAST(:viewerId AS uuid) IS NULL OR NOT EXISTS (
              SELECT 1 FROM user_blocks ub
              WHERE (ub.blocker_id = :viewerId AND ub.blocked_id = u.id)
                 OR (ub.blocker_id = u.id AND ub.blocked_id = :viewerId)))
        """, nativeQuery = true)
    boolean recommandeParDesParticipants(@Param("programId") UUID programId,
                                         @Param("viewerId") UUID viewerId);

    Optional<Review> findByReviewerIdAndProgramId(UUID reviewerId, UUID programId);

    long countByProgramId(UUID programId);

    long countByReviewerId(UUID reviewerId);

    List<Review> findByReviewerId(UUID reviewerId);

    @Modifying
    @Query("UPDATE ReviewPhase3 r SET r.reviewerId = null, r.comment = '[Avis anonymisé]' WHERE r.reviewerId = :reviewerId")
    void anonymizeByReviewerId(@Param("reviewerId") UUID reviewerId);
}
