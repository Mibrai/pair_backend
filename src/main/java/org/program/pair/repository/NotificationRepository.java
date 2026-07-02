package org.program.pair.repository;

import org.program.pair.domain.notification.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderBySentAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(UUID userId);

    java.util.List<Notification> findByUserIdAndIsReadFalse(UUID userId);

    /**
     * Find all notifications for a user (for GDPR export)
     */
    java.util.List<Notification> findByUserId(UUID userId);

    /**
     * Count all notifications for a user (for GDPR export)
     */
    long countByUserId(UUID userId);
}
