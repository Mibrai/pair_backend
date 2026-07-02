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

    @Query("SELECT u FROM User u WHERE u.id IN :ids AND u.lastActiveAt > :since AND u.onlineStatusVisible = true")
    List<User> findOnlineUsers(@Param("ids") List<UUID> ids, @Param("since") Instant since);

    /**
     * Find inactive accounts for GDPR purge (Article 17)
     * Accounts that have been inactive for more than 30 days
     */
    @Query("SELECT u FROM User u WHERE u.isActive = false AND u.lastActiveAt < :cutoff")
    List<User> findInactiveAccountsBefore(@Param("cutoff") Instant cutoff);
}
