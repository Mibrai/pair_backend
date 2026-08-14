package org.program.pair.repository;

import org.program.pair.domain.recap.SlotRecap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotRecapRepository extends JpaRepository<SlotRecap, UUID> {

    Optional<SlotRecap> findByScheduleId(UUID scheduleId);

    /**
     * Cartes publiques autour d'un point.
     *
     * <p>Les filtres de visibilité sont ceux de
     * {@link ScheduleRepository#findOpenSlotsInRadius} : programme public,
     * auteur actif, activité visible sur la carte. Diverger ici ferait
     * réapparaître dans le fil des souvenirs un créneau que le reste de l'API
     * a décidé de ne plus montrer.
     *
     * <p>Ce qui diffère, et seulement cela : on regarde des créneaux
     * <b>passés</b>, donc ni {@code status IN ('OPEN','FULL')} ni fenêtre de
     * dates à venir, et le programme n'a pas à être encore {@code ACTIVE} —
     * une carte est la trace d'un moment qui a eu lieu, pas une invitation à
     * s'inscrire.
     */
    @Query(value = """
        SELECT r.* FROM slot_recaps r
        JOIN schedules s        ON r.schedule_id = s.id
        JOIN programs p         ON s.program_id = p.id
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u            ON ua.user_id = u.id
        WHERE r.visibility = 'PUBLIC'
          AND r.published_at IS NOT NULL
          AND p.is_public = TRUE
          AND u.is_active = TRUE
          AND ua.visible_on_map = TRUE
          AND s.location IS NOT NULL
          AND ST_DWithin(
                s.location::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters)
        ORDER BY r.published_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<SlotRecap> findPublicInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );

    /**
     * Cartes des créneaux où j'étais — présence confirmée, pas simple
     * inscription : la carte appartient à ceux qui y étaient.
     */
    @Query("""
        SELECT r FROM SlotRecap r
        WHERE EXISTS (
            SELECT 1 FROM Attendance a
            WHERE a.schedule.id = r.schedule.id
              AND a.user.id = :userId
              AND a.wasPresent = true)
        ORDER BY r.schedule.startsAt DESC
        """)
    List<SlotRecap> findMine(@Param("userId") UUID userId);
}
