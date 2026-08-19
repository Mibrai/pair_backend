package org.program.pair.repository;

import org.program.pair.domain.availability.UserAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserAvailabilityRepository extends JpaRepository<UserAvailability, UserAvailability.Id> {

    @Query("""
        SELECT a FROM UserAvailability a
        WHERE a.id.userId = :userId
        ORDER BY a.id.dayOfWeek, a.id.timeSlot
        """)
    List<UserAvailability> findByUserId(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserAvailability a WHERE a.id.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
