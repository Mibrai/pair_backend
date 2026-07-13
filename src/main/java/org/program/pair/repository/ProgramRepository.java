package org.program.pair.repository;

import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    @Query("SELECT COUNT(p) FROM Program p WHERE p.userActivity.user.id = :userId")
    long countProgramsByUser(@Param("userId") UUID userId);

    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND ua.visible_on_map = true
          AND p.embedding IS NOT NULL
          AND (p.embedding <=> CAST(:queryEmbedding AS vector)) <= :maxDistance
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY p.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> semanticSearchInRadius(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("maxDistance") double maxDistance,
        @Param("limit") int limit
    );

    List<Program> findByUserActivityUserIdAndStatusNot(UUID userId, ProgramStatus status);

    List<Program> findByUserActivityId(UUID userActivityId);

    @Query("SELECT COUNT(p) FROM Program p WHERE p.userActivity.user.id = :userId AND p.status = 'ACTIVE'")
    int countActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE programs SET embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);

    @Query(value = "SELECT * FROM programs WHERE embedding IS NULL", nativeQuery = true)
    List<Program> findByEmbeddingIsNull();

    /**
     * Find programs by organizer (user) ID for GDPR export
     */
    @Query("SELECT p FROM Program p WHERE p.userActivity.user.id = :organisateurId")
    List<Program> findByOrganisateurId(@Param("organisateurId") UUID organisateurId);

    /**
     * Find active, public programs created by a given user (for public profile view).
     */
    @Query("SELECT p FROM Program p WHERE p.userActivity.user.id = :userId AND p.status = 'ACTIVE' AND p.isPublic = true")
    List<Program> findActivePublicByUserId(@Param("userId") UUID userId);

    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND u.location_public = true
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> findVisibleInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );

    /**
     * Same visibility filter as findVisibleInRadius, but matches on where the
     * program actually takes place (its nearest schedule location, same source
     * GET /map/activities uses for its markers) instead of the organizer's own
     * profile location. Falls back to the organizer's location only when the
     * program has no schedule with a location.
     */
    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        LEFT JOIN LATERAL (
            SELECT s.location AS loc
            FROM schedules s
            WHERE s.program_id = p.id AND s.location IS NOT NULL
            ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
            LIMIT 1
        ) nearest_schedule ON true
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND ST_DWithin(
              COALESCE(nearest_schedule.loc, CASE WHEN u.location_public THEN u.location END)::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY ST_Distance(
            COALESCE(nearest_schedule.loc, u.location)::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> findVisibleNearScheduleOrOrganizer(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );
}
