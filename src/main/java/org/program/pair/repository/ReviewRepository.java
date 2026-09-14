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

    Optional<Review> findByReviewerIdAndProgramId(UUID reviewerId, UUID programId);

    long countByProgramId(UUID programId);

    long countByReviewerId(UUID reviewerId);

    List<Review> findByReviewerId(UUID reviewerId);

    @Modifying
    @Query("UPDATE ReviewPhase3 r SET r.reviewerId = null, r.comment = '[Avis anonymisé]' WHERE r.reviewerId = :reviewerId")
    void anonymizeByReviewerId(@Param("reviewerId") UUID reviewerId);
}
