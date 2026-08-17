package org.program.pair.repository;

import org.program.pair.domain.recap.SlotRecap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotRecapRepository extends JpaRepository<SlotRecap, UUID> {

    /**
     * La carte d'une séance précise. C'est la clé réelle depuis que les
     * occurrences sont distinguées de la ligne de créneau : sur une série
     * hebdomadaire, {@code schedule_id} seul en désigne plusieurs.
     */
    Optional<SlotRecap> findByScheduleIdAndOccurrenceStart(UUID scheduleId, Instant occurrenceStart);

    /**
     * Les cartes d'un créneau, de la séance la plus récente à la plus
     * ancienne. Une seule ligne pour un créneau non récurrent.
     */
    List<SlotRecap> findByScheduleIdOrderByOccurrenceStartDesc(UUID scheduleId);

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
     * Cartes des séances où j'étais — présence confirmée, pas simple
     * inscription : la carte appartient à ceux qui y étaient.
     *
     * <p>La jointure porte sur la séance ({@code attendedAt = occurrenceStart})
     * et non sur le créneau : être venu une fois à un cours hebdomadaire ne
     * fait pas de ses autres semaines mes souvenirs.
     */
    @Query("""
        SELECT r FROM SlotRecap r
        WHERE EXISTS (
            SELECT 1 FROM Attendance a
            WHERE a.schedule.id = r.schedule.id
              AND a.attendedAt = r.occurrenceStart
              AND a.user.id = :userId
              AND a.wasPresent = true)
        ORDER BY r.occurrenceStart DESC
        """)
    List<SlotRecap> findMine(@Param("userId") UUID userId);

    // ————————————————————— lectures contextuelles —————————————————————
    //
    // Ni position ni rayon : ces trois pages sont atteintes délibérément — on
    // regarde CE programme, CETTE activité, CE profil. Le fil géolocalisé, lui,
    // répond à « autour de moi », une question que personne ne pose sur la page
    // d'un programme situé à Munich.

    /**
     * Cartes d'un programme, avec la visibilité graduée déjà en vigueur
     * ailleurs : un visiteur voit les cartes publiques, un participant y ajoute
     * les séances où il était — quelle que soit leur visibilité, comme le lui
     * rend déjà {@code /recaps/mine} —, et l'auteur voit tout son programme.
     *
     * <p>L'auteur d'un programme en est aussi l'hôte : {@code userActivity.user}
     * sert donc les deux rôles, et un programme privé n'est lisible que par lui.
     */
    @Query("""
        SELECT r FROM SlotRecap r
        WHERE r.schedule.program.id = :programId
          AND r.schedule.program.userActivity.user.isActive = true
          AND (r.schedule.program.isPublic = true
               OR r.schedule.program.userActivity.user.id = :requesterId)
          AND (r.visibility = org.program.pair.domain.recap.RecapVisibility.PUBLIC
               OR r.schedule.program.userActivity.user.id = :requesterId
               OR EXISTS (
                    SELECT 1 FROM Attendance a
                    WHERE a.schedule.id = r.schedule.id
                      AND a.attendedAt = r.occurrenceStart
                      AND a.user.id = :requesterId
                      AND a.wasPresent = true))
        ORDER BY r.occurrenceStart DESC
        """)
    List<SlotRecap> findForProgram(@Param("programId") UUID programId,
                                   @Param("requesterId") UUID requesterId);

    /**
     * Cartes publiques d'une activité du catalogue, tous organisateurs
     * confondus.
     *
     * <p>Publiques uniquement, sans exception : c'est une surface de
     * découverte, où l'on arrive sans rien connaître de personne. Le filtre
     * {@code visibleOnMap} est celui du catalogue lui-même
     * ({@code ActivityRepository}, {@code /activities/browse}) — une activité
     * qu'un organisateur a retirée de la découverte ne doit pas y revenir par
     * ses souvenirs.
     */
    @Query("""
        SELECT r FROM SlotRecap r
        WHERE r.schedule.program.userActivity.activity.id = :activityId
          AND r.visibility = org.program.pair.domain.recap.RecapVisibility.PUBLIC
          AND r.publishedAt IS NOT NULL
          AND r.schedule.program.isPublic = true
          AND r.schedule.program.userActivity.user.isActive = true
          AND r.schedule.program.userActivity.visibleOnMap = true
        ORDER BY r.occurrenceStart DESC
        """)
    List<SlotRecap> findPublicForActivity(@Param("activityId") UUID activityId);

    /**
     * Cartes publiques des créneaux animés par quelqu'un.
     *
     * <p>Publiques uniquement, y compris pour un participant de la séance : une
     * carte privée d'un tiers n'a pas à apparaître sur un profil, qui est une
     * page de présentation et non le cercle de ceux qui y étaient. Ceux-là la
     * retrouvent par {@code /recaps/mine}.
     */
    @Query("""
        SELECT r FROM SlotRecap r
        WHERE r.schedule.program.userActivity.user.id = :userId
          AND r.visibility = org.program.pair.domain.recap.RecapVisibility.PUBLIC
          AND r.publishedAt IS NOT NULL
          AND r.schedule.program.isPublic = true
          AND r.schedule.program.userActivity.user.isActive = true
        ORDER BY r.occurrenceStart DESC
        """)
    List<SlotRecap> findPublicForHost(@Param("userId") UUID userId);
}
