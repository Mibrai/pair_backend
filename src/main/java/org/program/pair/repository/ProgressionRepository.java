package org.program.pair.repository;

import org.program.pair.domain.progression.Progression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProgressionRepository extends JpaRepository<Progression, UUID> {

    Page<Progression> findByProgramIdOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    Page<Progression> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Progression> findByProgramIdAndIsPublicTrueOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    @Query("SELECT p FROM Progression p WHERE p.user.id = :userId AND p.createdAt >= :startDate ORDER BY p.createdAt DESC")
    List<Progression> findByUserIdAndCreatedAtAfter(@Param("userId") UUID userId, @Param("startDate") Instant startDate);

    @Query("SELECT DATE(p.createdAt) as date, COUNT(p) FROM Progression p WHERE p.user.id = :userId GROUP BY DATE(p.createdAt) ORDER BY DATE(p.createdAt) DESC")
    List<Object[]> findProgressionDatesByUserId(@Param("userId") UUID userId);

    int countByUserId(UUID userId);

    int countByUserIdAndIsPublicTrue(UUID userId);

    int countByUserIdAndIsPublicFalse(UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Find progressions for programs organized by a specific user (for GDPR export)
     */
    @Query("SELECT p FROM Progression p WHERE p.program.organisateur.id = :organisateurId")
    List<Progression> findByProgramOrganisateurId(@Param("organisateurId") UUID organisateurId);
}
