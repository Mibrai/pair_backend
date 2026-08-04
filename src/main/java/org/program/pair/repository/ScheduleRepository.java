package org.program.pair.repository;

import jakarta.persistence.LockModeType;
import org.program.pair.domain.program.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByStartsAtBetween(Instant from, Instant to);

    List<Schedule> findByProgramId(UUID programId);

    @Query("SELECT s FROM Schedule s " +
           "LEFT JOIN FETCH s.program p " +
           "LEFT JOIN FETCH p.userActivity ua " +
           "LEFT JOIN FETCH ua.activity a " +
           "LEFT JOIN FETCH a.category " +
           "WHERE s.location IS NOT NULL")
    List<Schedule> findAllWithActivityDetails();

    /**
     * Ids des créneaux localisés à l'intérieur d'un rayon et/ou d'une bbox.
     *
     * <p>Ne renvoie que des ids : la reprise par
     * {@link #findWithActivityDetailsByIds} conserve les {@code LEFT JOIN FETCH}
     * qui évitent le N+1 sur program → userActivity → activity → category. Une
     * requête native renvoyant directement des entités les perdrait, et le
     * bornage se paierait en requêtes supplémentaires — l'inverse du but.
     *
     * <p>Chaque filtre est neutralisé quand ses paramètres sont nuls, ce qui
     * permet de demander un rayon seul, une bbox seule, ou l'intersection des
     * deux. L'opérateur {@code &&} (intersection de bbox) est indexable par le
     * GiST sur {@code location} ; pour un point il équivaut à l'inclusion.
     */
    @Query(value = """
        SELECT s.id FROM schedules s
        WHERE s.location IS NOT NULL
          AND (CAST(:radiusMeters AS double precision) IS NULL
               OR ST_DWithin(
                    s.location::geography,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters))
          AND (CAST(:south AS double precision) IS NULL
               OR s.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326))
        """, nativeQuery = true)
    List<UUID> findLocatedScheduleIdsWithin(@Param("lat") Double lat,
                                             @Param("lng") Double lng,
                                             @Param("radiusMeters") Integer radiusMeters,
                                             @Param("north") Double north,
                                             @Param("south") Double south,
                                             @Param("east") Double east,
                                             @Param("west") Double west);

    @Query("SELECT s FROM Schedule s " +
           "LEFT JOIN FETCH s.program p " +
           "LEFT JOIN FETCH p.userActivity ua " +
           "LEFT JOIN FETCH ua.activity a " +
           "LEFT JOIN FETCH a.category " +
           "WHERE s.location IS NOT NULL AND s.id IN :ids")
    List<Schedule> findWithActivityDetailsByIds(@Param("ids") Collection<UUID> ids);

    /**
     * Verrou pessimiste sur le schedule pendant la vérification de capacité,
     * pour que les deux mécanismes de participation (UserProgram et
     * SlotParticipation) ne puissent jamais dépasser ensemble maxParticipants
     * via une race condition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.id = :id")
    Optional<Schedule> lockById(@Param("id") UUID id);

    /**
     * Nombre de participants confirmés toutes sources confondues : inscriptions
     * actives à un programme structuré (UserProgram) + RSVP confirmés sur le
     * créneau lui-même (SlotParticipation). C'est la vérité unique utilisée
     * pour faire respecter Schedule.maxParticipants quel que soit le chemin
     * d'entrée.
     */
    @Query(value = """
        SELECT
          (SELECT COUNT(*) FROM user_programs up WHERE up.schedule_id = :scheduleId AND up.status = 'ACTIVE')
          +
          (SELECT COUNT(*) FROM slot_participations sp WHERE sp.schedule_id = :scheduleId AND sp.status = 'CONFIRMED')
        """, nativeQuery = true)
    long countConfirmedParticipants(@Param("scheduleId") UUID scheduleId);

    @Query("SELECT s FROM Schedule s WHERE s.program.userActivity.user.id = :userId " +
           "AND s.isOpenToPartners = true ORDER BY s.startsAt DESC")
    List<Schedule> findHostedOpenSlots(@Param("userId") UUID userId);

    @Query("SELECT s FROM Schedule s WHERE s.program.userActivity.user.id = :userId")
    List<Schedule> findHostedSchedules(@Param("userId") UUID userId);

    @Query("SELECT COUNT(s) FROM Schedule s WHERE s.program.userActivity.user.id = :userId")
    long countHostedByUserId(@Param("userId") UUID userId);

    @Query("SELECT s FROM Schedule s WHERE s.status IN ('OPEN', 'FULL') " +
           "AND ((s.endsAt IS NOT NULL AND s.endsAt BETWEEN :from AND :to) " +
           "OR (s.endsAt IS NULL AND s.startsAt BETWEEN :fromStart AND :toStart))")
    List<Schedule> findFinishedBetween(@Param("from") Instant from, @Param("to") Instant to,
                                        @Param("fromStart") Instant fromStart, @Param("toStart") Instant toStart);

    @Query("SELECT s FROM Schedule s WHERE s.status IN ('OPEN', 'FULL') AND s.startsAt < :cutoff")
    List<Schedule> findOpenOrFullStartedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Un schedule "récurrent" (recurrence_rule non nul, ex. "FREQ=WEEKLY;...")
     * n'a qu'une seule occurrence bookable (starts_at/ends_at) dans ce modèle
     * de données — rien n'expanse automatiquement les occurrences suivantes.
     * Une fois passée, cette unique occurrence doit être avancée du nombre de
     * semaines nécessaire pour retomber dans le futur, plutôt que de rester
     * PAST indéfiniment (voir RecurringSlotRolloverJob).
     */
    @Modifying
    @Query(value = """
        UPDATE schedules
        SET starts_at = starts_at + (CEIL(EXTRACT(EPOCH FROM (NOW() + INTERVAL '1 hour' - starts_at)) / 604800.0) * INTERVAL '7 days'),
            ends_at   = CASE WHEN ends_at IS NOT NULL
                             THEN ends_at + (CEIL(EXTRACT(EPOCH FROM (NOW() + INTERVAL '1 hour' - starts_at)) / 604800.0) * INTERVAL '7 days')
                             ELSE NULL END,
            status    = 'OPEN',
            participant_count = 0
        WHERE recurrence_rule IS NOT NULL
          AND starts_at < NOW()
        """, nativeQuery = true)
    int rollRecurringSchedulesForward();

    /**
     * Feed "autour de moi" — créneaux ouverts aux partenaires, à venir, dans le
     * rayon demandé. Ne retourne jamais un créneau dont l'hôte est inactif, ni
     * dont l'activité est masquée de la carte (mêmes filtres de visibilité que
     * ProgramRepository.findVisibleNearScheduleOrOrganizer).
     */
    @Query(value = """
        SELECT s.* FROM schedules s
        JOIN programs p         ON s.program_id = p.id
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u            ON ua.user_id = u.id
        WHERE s.is_open_to_partners = TRUE
          AND s.status IN ('OPEN', 'FULL')
          AND s.starts_at BETWEEN :fromTs AND :toTs
          AND p.status = 'ACTIVE'
          AND p.is_public = TRUE
          AND u.is_active = TRUE
          AND ua.visible_on_map = TRUE
          AND (CAST(:activityId AS uuid) IS NULL OR ua.activity_id = :activityId)
          AND (CAST(:categoryId AS uuid) IS NULL OR EXISTS (
                SELECT 1 FROM activities a
                WHERE a.id = ua.activity_id AND a.category_id = :categoryId))
          AND ST_DWithin(
                s.location::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters)
        ORDER BY s.starts_at ASC,
                 ST_Distance(s.location::geography,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
        LIMIT :limit
        """, nativeQuery = true)
    List<Schedule> findOpenSlotsInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("fromTs") Instant from,
        @Param("toTs") Instant to,
        @Param("activityId") UUID activityId,
        @Param("categoryId") UUID categoryId,
        @Param("limit") int limit
    );
}
