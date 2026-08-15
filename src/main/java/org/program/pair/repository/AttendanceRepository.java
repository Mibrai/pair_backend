package org.program.pair.repository;

import org.program.pair.domain.attendance.Attendance;
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
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    /**
     * Cet utilisateur a-t-il quoi que ce soit à voir avec ce créneau, toutes
     * séances confondues ? Volontairement au grain de la ligne, et non de
     * l'occurrence : c'est une question d'accès en lecture — quelqu'un qui est
     * venu une fois peut voir les cartes privées de la série.
     */
    boolean existsByScheduleIdAndUserId(UUID scheduleId, UUID userId);

    boolean existsByScheduleIdAndUserIdAndWasPresentTrue(UUID scheduleId, UUID userId);

    Optional<Attendance> findByScheduleIdAndUserId(UUID scheduleId, UUID userId);

    // ————————————————————— au grain de l'occurrence —————————————————————
    //
    // Tout ce qui décrit un MOMENT — l'effectif d'une carte, les photos qu'on
    // y voit, le droit d'y contribuer — se compte séance par séance. Les
    // variantes sans occurrence ci-dessus additionneraient toutes les séances
    // d'un créneau hebdomadaire dans une seule carte-souvenir. Le paramètre
    // porte le début de l'occurrence : voir SlotOccurrence.

    boolean existsByScheduleIdAndUserIdAndAttendedAt(UUID scheduleId, UUID userId, Instant occurrenceStart);

    boolean existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(
        UUID scheduleId, UUID userId, Instant occurrenceStart);

    Optional<Attendance> findByScheduleIdAndUserIdAndAttendedAt(
        UUID scheduleId, UUID userId, Instant occurrenceStart);

    /** Présents confirmés sur un créneau — un effectif, jamais un score. */
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.schedule.id = :scheduleId AND a.wasPresent = true")
    int countPresentByScheduleId(@Param("scheduleId") UUID scheduleId);

    /** Présents confirmés sur une séance précise. */
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.schedule.id = :scheduleId "
        + "AND a.attendedAt = :occurrenceStart AND a.wasPresent = true")
    int countPresentByOccurrence(@Param("scheduleId") UUID scheduleId,
                                 @Param("occurrenceStart") Instant occurrenceStart);

    /**
     * Y a-t-il, sur cette séance, quelqu'un d'autre que l'hôte à avoir confirmé
     * sa présence ? Garde-fou de publication d'une carte-souvenir : sans cela,
     * un hôte pourrait publier une carte laissant croire qu'un créneau a
     * rassemblé du monde alors qu'il y était seul.
     */
    boolean existsByScheduleIdAndAttendedAtAndWasPresentTrueAndUserIdNot(
        UUID scheduleId, Instant occurrenceStart, UUID userId);

    List<Attendance> findByScheduleIdAndWasPresentTrue(UUID scheduleId);

    List<Attendance> findByScheduleIdAndAttendedAtAndWasPresentTrue(
        UUID scheduleId, Instant occurrenceStart);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true")
    int countPresentByUserId(@Param("userId") UUID userId);

    /**
     * Nombre de PERSONNES DIFFÉRENTES avec qui l'utilisateur a pratiqué.
     * C'est la métrique de valeur centrale de meetDo — jamais un classement.
     */
    @Query(value = """
        SELECT COUNT(DISTINCT other.user_id)
        FROM attendances mine
        JOIN attendances other ON other.schedule_id = mine.schedule_id
                              AND other.user_id <> mine.user_id
        WHERE mine.user_id = :userId
          AND mine.was_present = TRUE
          AND other.was_present = TRUE
        """, nativeQuery = true)
    int countDistinctPartners(@Param("userId") UUID userId);

    @Query("SELECT a.attendedAt FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true ORDER BY a.attendedAt DESC")
    List<Instant> findPresentDatesDesc(@Param("userId") UUID userId);

    @Query("SELECT a.attendedAt FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true ORDER BY a.attendedAt DESC LIMIT 1")
    Optional<Instant> findLastAttendanceDate(@Param("userId") UUID userId);

    @Query("SELECT a.schedule.program.userActivity.activity.id, a.schedule.program.userActivity.activity.name, COUNT(a) " +
           "FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true " +
           "GROUP BY a.schedule.program.userActivity.activity.id, a.schedule.program.userActivity.activity.name")
    List<Object[]> countByActivityForUser(@Param("userId") UUID userId);

    @Query("SELECT a.user FROM Attendance a WHERE a.schedule.id = :scheduleId " +
           "AND a.user.id <> :userId AND a.wasPresent = true")
    List<User> findPresentCoParticipants(@Param("scheduleId") UUID scheduleId, @Param("userId") UUID userId);

    @Query(value = """
        SELECT EXISTS (
          SELECT 1
          FROM attendances a1
          JOIN attendances a2 ON a1.schedule_id = a2.schedule_id
          WHERE a1.user_id = :userA AND a2.user_id = :userB
            AND a1.was_present = TRUE AND a2.was_present = TRUE
        )
        """, nativeQuery = true)
    boolean existsSharedPresence(@Param("userA") UUID userA, @Param("userB") UUID userB);
}
