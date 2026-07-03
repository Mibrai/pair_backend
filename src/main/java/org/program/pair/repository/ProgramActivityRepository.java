package org.program.pair.repository;

import org.program.pair.domain.program.ProgramActivity;
import org.program.pair.domain.program.ProgramActivityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProgramActivityRepository extends JpaRepository<ProgramActivity, UUID> {

    /**
     * Find activity by user program and activity ID
     */
    Optional<ProgramActivity> findByUserProgramIdAndActivityId(UUID userProgramId, UUID activityId);

    /**
     * Get all activities for a user program
     */
    List<ProgramActivity> findByUserProgramId(UUID userProgramId);

    /**
     * Count activities by status for a user program
     */
    long countByUserProgramIdAndStatus(UUID userProgramId, ProgramActivityStatus status);

    /**
     * Check if activity already exists
     */
    boolean existsByUserProgramIdAndActivityId(UUID userProgramId, UUID activityId);

    /**
     * Get completed activities count
     */
    @Query("SELECT COUNT(pa) FROM ProgramActivity pa WHERE pa.userProgram.id = :userProgramId AND pa.status = 'COMPLETED'")
    long countCompletedByUserProgramId(@Param("userProgramId") UUID userProgramId);

    /**
     * Get skipped activities count
     */
    @Query("SELECT COUNT(pa) FROM ProgramActivity pa WHERE pa.userProgram.id = :userProgramId AND pa.status = 'SKIPPED'")
    long countSkippedByUserProgramId(@Param("userProgramId") UUID userProgramId);
}
