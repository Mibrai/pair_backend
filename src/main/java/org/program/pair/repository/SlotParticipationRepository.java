package org.program.pair.repository;

import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.SlotParticipation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Créneaux passés auxquels cette personne s'était inscrite.
     *
     * <p>Le dénominateur du signal de fiabilité. Seuls les {@code CONFIRMED}
     * comptent : un désistement annoncé à l'avance n'est pas un manquement, et
     * le compter reviendrait à punir le geste honnête.
     */
    @Query("""
        SELECT COUNT(sp) FROM SlotParticipation sp
        WHERE sp.user.id = :userId
          AND sp.status = org.program.pair.domain.program.ParticipationStatus.CONFIRMED
          AND sp.schedule.startsAt < :now
        """)
    int countPastJoinedByUserId(@Param("userId") UUID userId, @Param("now") java.time.Instant now);
}
