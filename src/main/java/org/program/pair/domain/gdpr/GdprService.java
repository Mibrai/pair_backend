package org.program.pair.domain.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.gdpr.dto.GdprExportDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GDPR Compliance Service
 * Implements EU GDPR requirements (Articles 15, 17, 20)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdprService {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;
    private final PeerRecommendationRepository recommendationRepository;
    private final ProgressionRepository progressionRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final SearchLogRepository searchLogRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final AuditLogService auditLogService;

    /**
     * Export all user data (GDPR Article 15: Right of access)
     */
    @Transactional(readOnly = true)
    public GdprExportDto exportUserData(UUID userId) {
        log.info("Exporting GDPR data for user {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Log the export action
        auditLogService.log(userId, AuditActionType.GDPR_EXPORT, "USER", userId);

        // Collect all user data
        GdprExportDto export = GdprExportDto.builder()
                .exportDate(Instant.now())
                .exportedBy(user.getEmail())
                .user(buildUserData(user))
                .activities(buildActivitiesData(userId))
                .programs(buildProgramsData(userId))
                .messages(buildMessagesData(userId))
                .reviews(buildReviewsData(userId))
                .recommendations(buildRecommendationsData(userId))
                .progressions(buildProgressionsData(userId))
                .notifications(buildNotificationsData(userId))
                .auditLogs(buildAuditLogsData(userId))
                .statistics(buildStatistics(userId))
                .build();

        log.info("GDPR export completed for user {}: {} data points",
                userId, calculateDataPoints(export));

        return export;
    }

    /**
     * Purge inactive accounts (GDPR Article 17: Right to erasure + Article 5.1.e)
     * Called by scheduled job
     */
    @Transactional
    public int purgeInactiveAccounts() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        log.info("Purging accounts deactivated before {}", cutoff);

        List<User> inactiveUsers = userRepository.findInactiveAccountsBefore(cutoff);

        for (User user : inactiveUsers) {
            try {
                anonymizeUserData(user.getId());
                log.info("Purged inactive account: {}", user.getId());
            } catch (Exception e) {
                log.error("Failed to purge account {}", user.getId(), e);
            }
        }

        return inactiveUsers.size();
    }

    /**
     * Anonymize all user data (GDPR Article 17: Right to erasure)
     */
    @Transactional
    public void anonymizeUserData(UUID userId) {
        log.info("Anonymizing all data for user {}", userId);

        // Log the anonymization
        auditLogService.log(userId, AuditActionType.GDPR_ANONYMIZE, "USER", userId);

        // Anonymize messages
        messageRepository.anonymizeBySenderId(userId);

        // Anonymize reviews
        reviewRepository.anonymizeByReviewerId(userId);

        // Anonymize recommendations
        recommendationRepository.anonymizeByRecommenderId(userId);

        // Delete search logs
        searchLogRepository.deleteByUserId(userId);

        // Anonymize audit logs
        auditLogRepository.anonymizeByUserId(userId);

        // Delete user entity last
        userRepository.deleteById(userId);

        log.info("User {} fully anonymized", userId);
    }

    // ========== Private Helper Methods ==========

    private GdprExportDto.UserDataDto buildUserData(User user) {
        return GdprExportDto.UserDataDto.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .nom(user.getDisplayName()) // Using displayName for nom
                .prenom("") // No separate prenom field
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .location(user.getLocation() != null ? GdprExportDto.LocationDto.builder()
                        .latitude(user.getLocation().getY())
                        .longitude(user.getLocation().getX())
                        .blurRadiusM(user.getBlurRadiusM())
                        .build() : null)
                .createdAt(user.getCreatedAt())
                .lastActiveAt(user.getLastActiveAt())
                .accountStatus(user.getIsActive() ? "ACTIVE" : "INACTIVE")
                .build();
    }

    private List<GdprExportDto.ActivityDataDto> buildActivitiesData(UUID userId) {
        return userActivityRepository.findByUserId(userId).stream()
                .map(ua -> GdprExportDto.ActivityDataDto.builder()
                        .id(ua.getId().toString())
                        .activityName(ua.getActivity() != null ? ua.getActivity().getName() : null)
                        .categoryName(ua.getActivity() != null && ua.getActivity().getCategory() != null
                                ? ua.getActivity().getCategory().getName() : null)
                        .level(ua.getLevel() != null ? ua.getLevel().name() : null)
                        .format(ua.getFormat() != null ? ua.getFormat().name() : null)
                        .visibleOnMap(ua.getVisibleOnMap())
                        .addedAt(ua.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ProgramDataDto> buildProgramsData(UUID userId) {
        return programRepository.findByOrganisateurId(userId).stream()
                .map(p -> GdprExportDto.ProgramDataDto.builder()
                        .id(p.getId().toString())
                        .titre(p.getTitle())
                        .description(p.getDescription())
                        .activityName(p.getUserActivity() != null && p.getUserActivity().getActivity() != null
                                ? p.getUserActivity().getActivity().getName() : null)
                        .status(p.getStatus().name())
                        .schedules(p.getSchedules().stream()
                                .map(s -> GdprExportDto.ScheduleDto.builder()
                                        .id(s.getId().toString())
                                        .jourSemaine(s.getRecurrenceRule()) // Using recurrence rule instead of day of week
                                        .heureDebut(s.getStartsAt() != null ? s.getStartsAt().toString() : null)
                                        .heureFin(s.getEndsAt() != null ? s.getEndsAt().toString() : null)
                                        .location(s.getLocation() != null ? GdprExportDto.LocationDto.builder()
                                                .latitude(s.getLocation().getY())
                                                .longitude(s.getLocation().getX())
                                                .build() : null)
                                        .build())
                                .collect(Collectors.toList()))
                        .createdAt(p.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.MessageDataDto> buildMessagesData(UUID userId) {
        return messageRepository.findBySenderId(userId).stream()
                .map(m -> GdprExportDto.MessageDataDto.builder()
                        .id(m.getId().toString())
                        .conversationId(m.getConversation().getId().toString())
                        .content(m.getContent())
                        .sentByMe(true)
                        .sentAt(m.getSentAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ReviewDataDto> buildReviewsData(UUID userId) {
        return reviewRepository.findByReviewerId(userId).stream()
                .map(r -> GdprExportDto.ReviewDataDto.builder()
                        .id(r.getId().toString())
                        .programTitle(r.getProgram() != null ? r.getProgram().getTitle() : null)
                        .score(r.getScore())
                        .comment(r.getComment())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.RecommendationDataDto> buildRecommendationsData(UUID userId) {
        return recommendationRepository.findByRecommenderId(userId).stream()
                .map(r -> GdprExportDto.RecommendationDataDto.builder()
                        .id(r.getId().toString())
                        .recommendedUserName(r.getRecommended() != null
                                ? r.getRecommended().getDisplayName()
                                : "Unknown")
                        .comment(r.getComment())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ProgressionDataDto> buildProgressionsData(UUID userId) {
        return progressionRepository.findByProgramOrganisateurId(userId).stream()
                .map(p -> GdprExportDto.ProgressionDataDto.builder()
                        .id(p.getId().toString())
                        .programTitle(p.getProgram() != null ? p.getProgram().getTitle() : null)
                        .label(p.getTitle())
                        .value(p.getContent())
                        .recordedAt(p.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.NotificationDataDto> buildNotificationsData(UUID userId) {
        return notificationRepository.findByUserId(userId).stream()
                .map(n -> GdprExportDto.NotificationDataDto.builder()
                        .id(n.getId().toString())
                        .type(n.getType().name())
                        .title(n.getType().name()) // Notification doesn't have title field
                        .message(n.getPayload()) // Using payload as message
                        .read(n.getReadAt() != null)
                        .createdAt(n.getSentAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.AuditLogDataDto> buildAuditLogsData(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 1000)
        ).stream()
                .map(a -> GdprExportDto.AuditLogDataDto.builder()
                        .id(a.getId().toString())
                        .actionType(a.getActionType().name())
                        .entityType(a.getEntityType())
                        .ipAddress(a.getIpAddress())
                        .timestamp(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private Map<String, Long> buildStatistics(UUID userId) {
        Map<String, Long> stats = new HashMap<>();
        stats.put("activities", (long) userActivityRepository.findByUserId(userId).size());
        stats.put("programs", (long) programRepository.findByOrganisateurId(userId).size());
        stats.put("messages", (long) messageRepository.findBySenderId(userId).size());
        stats.put("reviews", (long) reviewRepository.findByReviewerId(userId).size());
        stats.put("recommendations", (long) recommendationRepository.findByRecommenderId(userId).size());
        stats.put("progressions", (long) progressionRepository.findByProgramOrganisateurId(userId).size());
        stats.put("notifications", notificationRepository.countByUserId(userId));
        stats.put("conversations", (long) conversationMemberRepository.findConversationsByUserId(userId).size());
        return stats;
    }

    private int calculateDataPoints(GdprExportDto export) {
        return (export.getActivities() != null ? export.getActivities().size() : 0) +
               (export.getPrograms() != null ? export.getPrograms().size() : 0) +
               (export.getMessages() != null ? export.getMessages().size() : 0) +
               (export.getReviews() != null ? export.getReviews().size() : 0) +
               (export.getRecommendations() != null ? export.getRecommendations().size() : 0) +
               (export.getProgressions() != null ? export.getProgressions().size() : 0) +
               (export.getNotifications() != null ? export.getNotifications().size() : 0) +
               (export.getAuditLogs() != null ? export.getAuditLogs().size() : 0);
    }
}
