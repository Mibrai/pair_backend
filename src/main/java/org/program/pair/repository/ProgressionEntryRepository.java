package org.program.pair.repository;

import org.program.pair.domain.support.ProgressionEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProgressionEntryRepository extends JpaRepository<ProgressionEntry, UUID> {

    Page<ProgressionEntry> findByProgramIdOrderByCreatedAtDesc(UUID programId, Pageable pageable);

    @Query(value = """
        SELECT COUNT(DISTINCT DATE(created_at))
        FROM progression_entries
        WHERE user_id = :userId
          AND created_at >= CURRENT_DATE - INTERVAL '30 days'
        """, nativeQuery = true)
    int getCurrentStreak(@Param("userId") UUID userId);
}
