package org.program.pair.repository;

import org.program.pair.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
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
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> findVisibleUsersInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    /**
     * Utilisateurs visibles à l'intérieur d'une bbox.
     *
     * <p>Remplace, pour {@code GET /map/bounds}, l'approximation « rayon déduit
     * de la bbox puis filtre en Java » : ce rayon valait
     * {@code max(latDiff, lngDiff) * 111320 / 2}, plafonné à 100 km. Un disque
     * inscrit ne couvre pas les coins du rectangle, et le plafond rognait
     * silencieusement les grandes zones — des utilisateurs pourtant dans les
     * bornes n'étaient jamais renvoyés.
     *
     * <p>Tri par distance au centre de la bbox : {@code limit} garde alors les
     * plus proches du centre de l'écran, pas un échantillon arbitraire. Le
     * départage sur l'id rend l'ordre total, donc la pagination stable.
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND u.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint((:west + :east) / 2, (:south + :north) / 2), 4326)::geography
        ), u.id
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> findVisibleUsersInBounds(
        @Param("south") double south,
        @Param("north") double north,
        @Param("west") double west,
        @Param("east") double east,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    /** Total exact avant application de {@code limit}, pour {@code totalInBounds}. */
    @Query(value = """
        SELECT COUNT(*) FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND u.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
        """, nativeQuery = true)
    long countVisibleUsersInBounds(
        @Param("south") double south,
        @Param("north") double north,
        @Param("west") double west,
        @Param("east") double east
    );

    @Query("SELECT u FROM User u WHERE u.id IN :ids AND u.lastActiveAt > :since AND u.onlineStatusVisible = true")
    List<User> findOnlineUsers(@Param("ids") List<UUID> ids, @Param("since") Instant since);

    /**
     * Find inactive accounts for GDPR purge (Article 17)
     * Accounts that have been inactive for more than 30 days
     */
    @Query("SELECT u FROM User u WHERE u.isActive = false AND u.lastActiveAt < :cutoff")
    List<User> findInactiveAccountsBefore(@Param("cutoff") Instant cutoff);

    /**
     * Search users by name or bio with optional location filtering
     * Returns only active users with public locations
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND (
            LOWER(u.display_name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.bio) LIKE LOWER(CONCAT('%', :query, '%'))
          )
          AND (:lat IS NULL OR :lng IS NULL OR ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          ))
        ORDER BY
          CASE WHEN :lat IS NULL OR :lng IS NULL THEN 0
          ELSE ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
          )
          END
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> searchUsers(
        @Param("query") String query,
        @Param("lat") Double lat,
        @Param("lng") Double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    /**
     * Count search results for pagination
     */
    @Query(value = """
        SELECT COUNT(*) FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND (
            LOWER(u.display_name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.bio) LIKE LOWER(CONCAT('%', :query, '%'))
          )
          AND (:lat IS NULL OR :lng IS NULL OR ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          ))
        """, nativeQuery = true)
    long countSearchResults(
        @Param("query") String query,
        @Param("lat") Double lat,
        @Param("lng") Double lng,
        @Param("radiusMeters") int radiusMeters
    );
}
