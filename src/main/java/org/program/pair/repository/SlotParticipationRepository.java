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
}
