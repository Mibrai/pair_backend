package org.program.pair.domain.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Service for audit logging (GDPR compliance)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Log an audit event asynchronously
     */
    @Async
    public void log(
            UUID userId,
            AuditActionType actionType,
            String entityType,
            UUID entityId
    ) {
        log(userId, actionType, entityType, entityId, null, null);
    }

    /**
     * Log an audit event with old/new values
     */
    @Async
    public void log(
            UUID userId,
            AuditActionType actionType,
            String entityType,
            UUID entityId,
            Object oldValue,
            Object newValue
    ) {
        try {
            HttpServletRequest request = getCurrentRequest();

            AuditLog auditLog = AuditLog.builder()
                    .userId(userId)
                    .actionType(actionType)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(toJson(oldValue))
                    .newValue(toJson(newValue))
                    .ipAddress(request != null ? getClientIP(request) : null)
                    .userAgent(request != null ? request.getHeader("User-Agent") : null)
                    .createdAt(Instant.now())
                    .build();

            auditLogRepository.save(auditLog);

            log.debug("Audit log created: {} by user {} on {} {}",
                    actionType, userId, entityType, entityId);

        } catch (Exception e) {
            log.error("Failed to create audit log", e);
            // Don't throw - audit log failure should not block main operation
        }
    }

    /**
     * Get audit logs for a user (GDPR Article 15: right of access)
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getUserAuditLogs(UUID userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Get audit logs for a specific entity
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getEntityAuditLogs(String entityType, UUID entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId
        );
    }

    /**
     * Count user actions in time period
     */
    @Transactional(readOnly = true)
    public long countUserActions(UUID userId, Instant start, Instant end) {
        return auditLogRepository.countUserActionsBetween(userId, start, end);
    }

    /**
     * Anonymize audit logs for deleted user (GDPR Article 17)
     */
    @Transactional
    public void anonymizeUserLogs(UUID userId) {
        log.info("Anonymizing audit logs for user {}", userId);
        auditLogRepository.anonymizeByUserId(userId);
    }

    /**
     * Purge old audit logs (retention policy: 2 years)
     * GDPR Article 5.1.e: storage limitation
     */
    @Transactional
    public void purgeOldLogs() {
        Instant cutoff = Instant.now().minus(730, ChronoUnit.DAYS); // 2 years
        log.info("Purging audit logs older than {}", cutoff);
        auditLogRepository.deleteByCreatedAtBefore(cutoff);
    }

    /**
     * Convert object to JSON string
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON", e);
            return obj.toString();
        }
    }

    /**
     * Get current HTTP request
     */
    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes != null ? attributes.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get client IP address (handles proxies)
     */
    private String getClientIP(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_X_CLUSTER_CLIENT_IP",
                "HTTP_CLIENT_IP",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED",
                "HTTP_VIA",
                "REMOTE_ADDR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // Take first IP if multiple (X-Forwarded-For can have multiple IPs)
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }
}
