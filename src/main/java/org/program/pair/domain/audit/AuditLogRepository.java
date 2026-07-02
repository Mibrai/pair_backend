package org.program.pair.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Find all audit logs for a specific user
     */
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Find audit logs by action type
     */
    List<AuditLog> findByActionTypeAndCreatedAtAfter(
            AuditActionType actionType,
            Instant after
    );

    /**
     * Find audit logs for a specific entity
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType,
            UUID entityId
    );

    /**
     * Count actions by user in time period (for GDPR reporting)
     */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.userId = :userId " +
            "AND a.createdAt BETWEEN :start AND :end")
    long countUserActionsBetween(
            @Param("userId") UUID userId,
            @Param("start") Instant start,
            @Param("end") Instant end
    );

    /**
     * Delete old audit logs (retention policy)
     * GDPR Article 5.1.e: storage limitation
     */
    void deleteByCreatedAtBefore(Instant cutoff);

    /**
     * Anonymize audit logs for deleted user (GDPR Article 17)
     */
    @Modifying
    @Query("UPDATE AuditLog a SET a.userId = null, a.ipAddress = null, " +
            "a.userAgent = null WHERE a.userId = :userId")
    void anonymizeByUserId(@Param("userId") UUID userId);
}
