package org.program.pair.repository;

import org.program.pair.domain.alert.ActivityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityAlertRepository extends JpaRepository<ActivityAlert, UUID> {

    List<ActivityAlert> findByUserId(UUID userId);

    Optional<ActivityAlert> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndActivityId(UUID userId, UUID activityId);

    /**
     * ANTI-SPAM : une alerte ne se redéclenche pas plus d'une fois tous les
     * 7 jours pour un même utilisateur/activité, même si plusieurs créneaux
     * apparaissent.
     */
    @Query(value = """
        SELECT a.* FROM activity_alerts a
        JOIN users u ON a.user_id = u.id
        WHERE a.activity_id = :activityId
          AND a.is_active = TRUE
          AND u.is_active = TRUE
          AND (a.last_triggered_at IS NULL OR a.last_triggered_at < :cooldown)
          AND ST_DWithin(
                a.location::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                a.radius_meters)
        """, nativeQuery = true)
    List<ActivityAlert> findMatchingAlerts(
        @Param("activityId") UUID activityId,
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("cooldown") Instant cooldown
    );
}
