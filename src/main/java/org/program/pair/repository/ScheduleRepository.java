package org.program.pair.repository;

import org.program.pair.domain.program.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByStartsAtBetween(Instant from, Instant to);

    List<Schedule> findByProgramId(UUID programId);
}
