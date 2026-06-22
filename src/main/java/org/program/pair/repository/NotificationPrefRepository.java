package org.program.pair.repository;

import org.program.pair.domain.notification.NotificationPref;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationPrefRepository extends JpaRepository<NotificationPref, UUID> {

    Optional<NotificationPref> findByUserIdAndNotificationType(UUID userId, NotificationType notificationType);

    List<NotificationPref> findByUserId(UUID userId);
}
