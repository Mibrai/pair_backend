package org.program.pair.repository;

import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.SlotParticipation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotParticipationRepository extends JpaRepository<SlotParticipation, UUID> {

    boolean existsByScheduleIdAndUserId(UUID scheduleId, UUID userId);

    boolean existsByScheduleIdAndUserIdAndStatus(UUID scheduleId, UUID userId, ParticipationStatus status);

    Optional<SlotParticipation> findByScheduleIdAndUserId(UUID scheduleId, UUID userId);

    List<SlotParticipation> findByScheduleId(UUID scheduleId);

    List<SlotParticipation> findByUserIdAndStatus(UUID userId, ParticipationStatus status);

    List<SlotParticipation> findByUserIdAndStatusIn(UUID userId, List<ParticipationStatus> statuses);

    /**
     * Mes participations sur <b>un lot de créneaux</b>, en une requête.
     *
     * <p>Le pendant par lot de {@link #findByScheduleIdAndUserId} et de
     * {@link #existsByScheduleIdAndUserIdAndStatus}. Rendre un fil de N créneaux
     * appelait ces deux-là une fois par élément — le statut de participation et
     * le rang en file d'attente d'un côté, la visibilité du lieu de l'autre —
     * soit jusqu'à deux allers-retours par créneau affiché, pour une information
     * qui tient dans une seule lecture.
     *
     * <p>Une personne a au plus une participation par créneau (contrainte
     * d'unicité sur le couple), donc le résultat s'indexe sans perte par id de
     * créneau. Un créneau absent de la table rendue signifie « aucune
     * participation », et non « pas encore chargé » : c'est ce qui permet à
     * l'appelant de ne jamais retomber sur le dépôt.
     */
    @Query("""
        SELECT sp FROM SlotParticipation sp
        WHERE sp.user.id = :userId AND sp.schedule.id IN :scheduleIds
        """)
    List<SlotParticipation> findByUserIdAndScheduleIdIn(@Param("userId") UUID userId,
                                                        @Param("scheduleIds") Collection<UUID> scheduleIds);

    @Query("SELECT COUNT(sp) FROM SlotParticipation sp " +
           "WHERE sp.schedule.id = :scheduleId AND sp.status = 'CONFIRMED'")
    long countConfirmedByScheduleId(@Param("scheduleId") UUID scheduleId);

    /**
     * La file d'attente d'un créneau, dans l'ordre où les gens s'y sont mis.
     *
     * <p>Le rang, et non la date de création : deux personnes inscrites dans la
     * même seconde doivent quand même avoir un ordre, et c'est celui-là qui fait
     * foi partout — affichage comme promotion.
     */
    @Query("""
        SELECT sp FROM SlotParticipation sp
        WHERE sp.schedule.id = :scheduleId AND sp.status = org.program.pair.domain.program.ParticipationStatus.WAITLISTED
        ORDER BY sp.waitlistPosition ASC
        """)
    List<SlotParticipation> findWaitlist(@Param("scheduleId") UUID scheduleId);

    @Query("""
        SELECT COALESCE(MAX(sp.waitlistPosition), 0) FROM SlotParticipation sp
        WHERE sp.schedule.id = :scheduleId AND sp.status = org.program.pair.domain.program.ParticipationStatus.WAITLISTED
        """)
    int lastWaitlistPosition(@Param("scheduleId") UUID scheduleId);

    /**
     * Créneaux passés auxquels cette personne s'était inscrite <b>et sur
     * lesquels elle a répondu</b>.
     *
     * <p>Le dénominateur du signal de fiabilité, et sa définition est le cœur du
     * lot C4. Deux exclusions, pour la même raison : ne jamais faire dire à un
     * silence ce qu'il ne dit pas.
     *
     * <p>Les désistements n'y sont pas — se décommander à l'avance n'est pas
     * manquer à sa parole, et le compter punirait le geste honnête. Les
     * <b>non-réponses</b> non plus : une question restée sans réponse peut
     * vouloir dire « je n'y étais pas », « j'ai oublié » ou « je ne l'ai jamais
     * reçue », et la compter au dénominateur reviendrait à trancher pour la
     * première hypothèse. Le signal mesure donc « sur ce qu'on sait », et un
     * silence retire la séance de la mesure au lieu de peser contre.
     */
    @Query("""
        SELECT COUNT(sp) FROM SlotParticipation sp
        WHERE sp.user.id = :userId
          AND sp.status = org.program.pair.domain.program.ParticipationStatus.CONFIRMED
          AND sp.schedule.startsAt < :now
          AND EXISTS (
              SELECT 1 FROM Attendance a
              WHERE a.user.id = sp.user.id AND a.schedule.id = sp.schedule.id)
        """)
    int countPastJoinedByUserId(@Param("userId") UUID userId, @Param("now") java.time.Instant now);

    /**
     * Les participations dont la fenêtre de confirmation est ouverte depuis trop
     * longtemps et qui n'ont reçu aucune réponse.
     *
     * <p>Alimente la fermeture à J+7. On borne aussi par le bas pour ne pas
     * reparcourir indéfiniment l'historique : au-delà, les fenêtres sont déjà
     * fermées.
     */
    @Query("""
        SELECT sp FROM SlotParticipation sp
        WHERE sp.status = org.program.pair.domain.program.ParticipationStatus.CONFIRMED
          AND sp.attendanceClosedAt IS NULL
          AND sp.schedule.startsAt < :closeBefore
          AND sp.schedule.startsAt > :scanFrom
          AND NOT EXISTS (
              SELECT 1 FROM Attendance a
              WHERE a.user.id = sp.user.id AND a.schedule.id = sp.schedule.id)
        """)
    List<SlotParticipation> findUnansweredToClose(
        @Param("closeBefore") java.time.Instant closeBefore,
        @Param("scanFrom") java.time.Instant scanFrom);
}
