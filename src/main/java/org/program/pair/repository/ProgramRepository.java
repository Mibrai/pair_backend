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
        @Param("limit") int limit
    );

    List<Program> findByUserActivityUserIdAndStatusNot(UUID userId, ProgramStatus status);

    List<Program> findByUserActivityId(UUID userActivityId);

    @Query("SELECT COUNT(p) FROM Program p WHERE p.userActivity.user.id = :userId AND p.status = 'ACTIVE'")
    int countActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE programs SET embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);
}
