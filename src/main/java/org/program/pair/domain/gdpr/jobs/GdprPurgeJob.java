package org.program.pair.domain.gdpr.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.gdpr.GdprService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job for GDPR compliance
 * Purges inactive accounts after 30 days (GDPR Article 17 + Article 5.1.e)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GdprPurgeJob {

    private final GdprService gdprService;
    private final AuditLogService auditLogService;

    /**
     * Purge inactive accounts daily at 3 AM
     * GDPR Article 17: Right to erasure
     * GDPR Article 5.1.e: Storage limitation
     */
    @Scheduled(cron = "0 0 3 * * *") // Every day at 3 AM
    public void purgeInactiveAccounts() {
        log.info("Starting GDPR purge job");

        try {
            int purgedCount = gdprService.purgeInactiveAccounts();
            log.info("GDPR purge job completed: {} accounts purged", purgedCount);
        } catch (Exception e) {
            log.error("GDPR purge job failed", e);
        }
    }

    /**
     * Purge old audit logs monthly (retention: 2 years)
     * GDPR Article 5.1.e: Storage limitation
     */
    @Scheduled(cron = "0 0 4 1 * *") // First day of month at 4 AM
    public void purgeOldAuditLogs() {
        log.info("Starting audit log purge job");

        try {
            auditLogService.purgeOldLogs();
            log.info("Audit log purge job completed");
        } catch (Exception e) {
            log.error("Audit log purge job failed", e);
        }
    }
}
