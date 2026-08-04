package org.program.pair.repository;

import org.program.pair.domain.activity.UserActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Une page de l'Explorer : la jointure activité × organisateur × programmes ×
     * créneaux que le client faisait jusqu'ici en trois requêtes, en indexant les
     * programmes <b>par nom d'activité</b>.
     *
     * <p><b>Maille : {@code user_activities}.</b> Une entrée est « cette
     * activité-là, chez cette personne-là ». C'est la seule maille qui rende
     * {@code organizerId} non ambigu, et elle règle par construction le cas de
     * deux activités homonymes chez deux organisateurs différents.
     *
     * <p><b>Localisation.</b> Une {@code UserActivity} n'a pas de coordonnées :
     * elle emprunte celles de son créneau localisé le plus proche du point
     * demandé. Sans créneau localisé, l'entrée n'a ni position ni distance —
     * c'est le cas des activités en ligne — et elle n'est alors <b>pas</b>
     * filtrée par le rayon, mais reléguée après toutes les entrées localisées
     * (voir l'ORDER BY), pour que les premières pages restent celles du
     * voisinage.
     *
     * <p><b>Ordre total.</b> distance, puis nom, puis {@code activityId}, puis
     * {@code userActivityId} : deux entrées ne peuvent pas rester à égalité, donc
     * la concaténation des pages est stable et sans doublon.
     *
     * <p><b>Expiration.</b> {@code isExpired} vaut vrai seulement si l'entrée est
     * datée — au moins un créneau — et qu'aucune occurrence future n'existe. Une
     * entrée sans aucun créneau n'est jamais expirée. Attention : les récurrences
     * ne sont pas développées (demande 4 non livrée), donc {@code nextSessionAt}
     * vaut ici le prochain {@code starts_at} brut, comme partout ailleurs dans
     * cette API.
     */
    @Query(value = """
        SELECT
            ua.id                                        AS userActivityId,
            a.id                                         AS activityId,
            a.name                                       AS activityName,
            a.icon                                       AS activityIcon,
            a.image_url                                  AS imageUrl,
            COALESCE(ua.custom_description, a.description) AS description,
            c.id                                         AS categoryId,
            c.name                                       AS categoryName,
            c.icon                                       AS categoryIcon,
            ST_Y(place.loc)                              AS lat,
            ST_X(place.loc)                              AS lng,
            place.address                                AS address,
            place.location_type                          AS locationType,
            CASE WHEN place.loc IS NULL THEN NULL ELSE ST_Distance(
                place.loc::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) END AS distanceMeters,
            u.id                                         AS organizerId,
            u.display_name                               AS organizerName,
            u.avatar_url                                 AS organizerAvatarUrl,
            COALESCE(stats.program_count, 0)             AS programCount,
            COALESCE(stats.total_participants, 0)        AS totalParticipants,
            agenda.next_session_at                       AS nextSessionAt,
            (agenda.schedule_count > 0 AND agenda.next_session_at IS NULL) AS isExpired
        FROM user_activities ua
        JOIN users u      ON ua.user_id = u.id
        JOIN activities a ON ua.activity_id = a.id
        JOIN categories c ON a.category_id = c.id
        LEFT JOIN LATERAL (
            SELECT s.location AS loc,
                   CASE WHEN s.place_type = 'PUBLIC' OR s.show_exact_address = TRUE
                        THEN s.address_public ELSE s.place_name END AS address,
                   p.location_type AS location_type
            FROM schedules s
            JOIN programs p ON s.program_id = p.id
            WHERE p.user_activity_id = ua.id
              AND s.location IS NOT NULL
              AND p.status = 'ACTIVE' AND p.is_public = TRUE
            ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
            LIMIT 1
        ) place ON TRUE
        LEFT JOIN LATERAL (
            SELECT COUNT(*) AS program_count,
                   COALESCE(SUM((
                       SELECT COUNT(*) FROM user_programs up
                       WHERE up.program_id = p.id AND up.status = 'ACTIVE'
                   )), 0) AS total_participants
            FROM programs p
            WHERE p.user_activity_id = ua.id
              AND p.status = 'ACTIVE' AND p.is_public = TRUE
        ) stats ON TRUE
        LEFT JOIN LATERAL (
            SELECT COUNT(*) AS schedule_count,
                   MIN(s.starts_at) FILTER (WHERE s.starts_at > NOW()) AS next_session_at
            FROM schedules s
            JOIN programs p ON s.program_id = p.id
            WHERE p.user_activity_id = ua.id
              AND p.status = 'ACTIVE' AND p.is_public = TRUE
        ) agenda ON TRUE
        WHERE ua.visible_on_map = TRUE
          AND u.is_active = TRUE
          AND (place.loc IS NULL OR ST_DWithin(
                place.loc::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters))
          AND (:includeExpired = TRUE
               OR NOT (agenda.schedule_count > 0 AND agenda.next_session_at IS NULL))
          AND (CAST(:categoryIds AS uuid[]) IS NULL OR c.id = ANY(CAST(:categoryIds AS uuid[])))
          AND (CAST(:levels AS text[]) IS NULL OR ua.level = ANY(CAST(:levels AS text[])))
        ORDER BY
            place.loc IS NULL,
            CASE WHEN :sortByNextSession THEN NULL ELSE
                CASE WHEN place.loc IS NULL THEN NULL ELSE ST_Distance(
                    place.loc::geography,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) END
            END,
            CASE WHEN :sortByNextSession THEN agenda.next_session_at END,
            a.name, a.id, ua.id
        """,
        countQuery = """
        SELECT COUNT(*)
        FROM user_activities ua
        JOIN users u      ON ua.user_id = u.id
        JOIN activities a ON ua.activity_id = a.id
        JOIN categories c ON a.category_id = c.id
        LEFT JOIN LATERAL (
            SELECT s.location AS loc
            FROM schedules s
            JOIN programs p ON s.program_id = p.id
            WHERE p.user_activity_id = ua.id
              AND s.location IS NOT NULL
              AND p.status = 'ACTIVE' AND p.is_public = TRUE
            ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
            LIMIT 1
        ) place ON TRUE
        LEFT JOIN LATERAL (
            SELECT COUNT(*) AS schedule_count,
                   MIN(s.starts_at) FILTER (WHERE s.starts_at > NOW()) AS next_session_at
            FROM schedules s
            JOIN programs p ON s.program_id = p.id
            WHERE p.user_activity_id = ua.id
              AND p.status = 'ACTIVE' AND p.is_public = TRUE
        ) agenda ON TRUE
        WHERE ua.visible_on_map = TRUE
          AND u.is_active = TRUE
          AND (place.loc IS NULL OR ST_DWithin(
                place.loc::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters))
          AND (:includeExpired = TRUE
               OR NOT (agenda.schedule_count > 0 AND agenda.next_session_at IS NULL))
          AND (CAST(:categoryIds AS uuid[]) IS NULL OR c.id = ANY(CAST(:categoryIds AS uuid[])))
          AND (CAST(:levels AS text[]) IS NULL OR ua.level = ANY(CAST(:levels AS text[])))
        """,
        nativeQuery = true)
    Page<ActivityBrowseRow> browse(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("includeExpired") boolean includeExpired,
        @Param("categoryIds") String categoryIds,
        @Param("levels") String levels,
        @Param("sortByNextSession") boolean sortByNextSession,
        Pageable pageable
    );

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
