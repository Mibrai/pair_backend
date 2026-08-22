package org.program.pair.repository;

import org.program.pair.domain.program.UserProgram;
import org.program.pair.domain.program.UserProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProgramRepository extends JpaRepository<UserProgram, UUID> {

    /**
     * Check if user is already enrolled in a program with active status
     */
    @Query("SELECT CASE WHEN COUNT(up) > 0 THEN true ELSE false END FROM UserProgram up " +
           "WHERE up.user.id = :userId AND up.program.id = :programId AND up.status = 'ACTIVE'")
    boolean existsByUserIdAndProgramIdAndStatusActive(@Param("userId") UUID userId,
                                                       @Param("programId") UUID programId);

    /**
     * Find active enrollment
     */
    Optional<UserProgram> findByUserIdAndProgramIdAndStatus(UUID userId, UUID programId, UserProgramStatus status);

    /**
     * Get all programs for a user with optional status filter
     */
    List<UserProgram> findByUserIdAndStatus(UUID userId, UserProgramStatus status);

    /**
     * Get all programs for a user (any status)
     */
    List<UserProgram> findByUserId(UUID userId);

    /**
     * Count active participants in a program
     */
    @Query("SELECT COUNT(up) FROM UserProgram up WHERE up.program.id = :programId AND up.status = 'ACTIVE'")
    long countActiveParticipantsByProgramId(@Param("programId") UUID programId);

    /**
     * Nombre d'inscrits actifs de plusieurs programmes, une ligne par programme
     * — {@code [UUID programId, Long nombre]}.
     *
     * <p><b>Un programme sans aucun inscrit actif n'a pas de ligne</b> : son
     * absence vaut zéro, et non « inconnu ». L'appelant qui la lirait comme un
     * {@code null} exposerait un compte manquant là où le compte est nul.
     */
    @Query("SELECT up.program.id, COUNT(up) FROM UserProgram up "
         + "WHERE up.program.id IN :programIds AND up.status = 'ACTIVE' "
         + "GROUP BY up.program.id")
    List<Object[]> countActiveParticipantsByProgramIds(@Param("programIds") Collection<UUID> programIds);

    /**
     * Count active participants for a specific schedule
     */
    @Query("SELECT COUNT(up) FROM UserProgram up WHERE up.schedule.id = :scheduleId AND up.status = 'ACTIVE'")
    long countActiveParticipantsByScheduleId(@Param("scheduleId") UUID scheduleId);

    /**
     * Get all active enrollments for a program
     */
    List<UserProgram> findByProgramIdAndStatus(UUID programId, UserProgramStatus status);

    /**
     * Identifiants des participants actifs d'un programme.
     *
     * <p>Destinataires d'une diffusion, et membres dérivés de son fil. Lue à
     * chaque envoi plutôt que conservée : c'est la liste du moment de l'envoi
     * qui doit être servie, pas celle d'une inscription passée.
     */
    @Query("SELECT up.user.id FROM UserProgram up " +
           "WHERE up.program.id = :programId AND up.status = 'ACTIVE'")
    List<UUID> findActiveParticipantIdsByProgramId(@Param("programId") UUID programId);

    /**
     * Find user programs by user for GDPR export (includes all statuses)
     */
    @Query("SELECT up FROM UserProgram up WHERE up.user.id = :userId")
    List<UserProgram> findAllByUserIdForGDPR(@Param("userId") UUID userId);
}
