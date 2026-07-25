package org.program.pair.repository;

import org.program.pair.domain.activity.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {

    List<UserActivity> findByUserId(UUID userId);

    Optional<UserActivity> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndActivityId(UUID userId, UUID activityId);

    Optional<UserActivity> findByUserIdAndActivityId(UUID userId, UUID activityId);

    @Query("SELECT ua FROM UserActivity ua WHERE ua.user.id = :userId AND ua.visibleOnMap = true")
    List<UserActivity> findVisibleByUserId(@Param("userId") UUID userId);

    @Query("SELECT ua.user.id FROM UserActivity ua WHERE ua.activity.id = :activityId AND ua.visibleOnMap = true")
    Set<UUID> findUserIdsByActivityIdAndVisible(@Param("activityId") UUID activityId);

    int countByUserId(UUID userId);

    @Query(value = """
        SELECT ua.* FROM user_activities ua
        JOIN users u ON ua.user_id = u.id
        WHERE ua.visible_on_map = true
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
    List<UserActivity> findVisibleInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );
}
