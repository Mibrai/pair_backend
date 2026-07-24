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

    boolean existsByScheduleIdAndUserId(UUID scheduleId, UUID userId);

    boolean existsByScheduleIdAndUserIdAndWasPresentTrue(UUID scheduleId, UUID userId);

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
