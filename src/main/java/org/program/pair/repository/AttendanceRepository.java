package org.program.pair.repository;

import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * La séance la plus récente de ce créneau où cette personne était
     * <b>réellement</b> présente.
     *
     * <p>Sert au module « affiche », dont le contrat désigne une affiche par le
     * seul {@code scheduleId} : sur une série hebdomadaire il faut bien décider
     * de quelle séance on parle, et c'est la dernière vécue qui est la bonne
     * réponse par défaut — les autres se nomment explicitement.
     *
     * <p><b>Passe par les présences et non par {@code SlotTiming}</b>, à rebours
     * du reste du module souvenir. La ligne de créneau ne connaît que deux
     * séances, celle qu'elle porte et celle que le rollover vient de retirer :
     * un cours suivi il y a un mois n'y figure plus, alors que la présence, elle,
     * ne s'efface pas. Une affiche doit rester publiable sur un souvenir ancien.
     */
    Optional<Attendance> findFirstByScheduleIdAndUserIdAndWasPresentTrueOrderByAttendedAtDesc(
        UUID scheduleId, UUID userId);

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

    /**
     * Les présences confirmées de <b>plusieurs</b> séances, en une requête.
     *
     * <p>Remplace un appel par carte de la variante ci-dessous, qui servait à
     * la fois les photos publiques, les participants nommés et le droit de
     * contribuer — trois lectures d'une même liste, payées carte par carte.
     *
     * <p>Les deux bornes sont croisées, pas appariées : la requête peut donc
     * ramener la présence d'une séance du 8 sur un créneau dont seule celle du
     * 15 était demandée. C'est l'appelant qui réapparie sur le couple
     * {@code (schedule_id, attended_at)} — le sur-ensemble est borné par la
     * page de cartes, et une requête de trop coûte moins qu'un aller-retour par
     * carte. Voir {@code SlotRecapService.RenderContext}.
     */
    @Query("""
        SELECT a FROM Attendance a
        JOIN FETCH a.user
        WHERE a.schedule.id IN :scheduleIds
          AND a.attendedAt IN :occurrenceStarts
          AND a.wasPresent = true
        """)
    List<Attendance> findPresentForOccurrences(
        @Param("scheduleIds") Collection<UUID> scheduleIds,
        @Param("occurrenceStarts") Collection<Instant> occurrenceStarts);

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
