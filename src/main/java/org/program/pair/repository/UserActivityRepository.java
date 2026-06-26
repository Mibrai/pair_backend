package org.program.pair.repository;

import org.program.pair.domain.activity.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {

    List<UserActivity> findByUserId(UUID userId);

    Optional<UserActivity> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndActivityId(UUID userId, UUID activityId);

    @Query("SELECT ua FROM UserActivity ua WHERE ua.user.id = :userId AND ua.visibleOnMap = true")
    List<UserActivity> findVisibleByUserId(@Param("userId") UUID userId);

    @Query("SELECT ua.user.id FROM UserActivity ua WHERE ua.activity.id = :activityId AND ua.visibleOnMap = true")
    Set<UUID> findUserIdsByActivityIdAndVisible(@Param("activityId") UUID activityId);

    int countByUserId(UUID userId);
}
