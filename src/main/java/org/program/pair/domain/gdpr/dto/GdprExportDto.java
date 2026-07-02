package org.program.pair.domain.gdpr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO for GDPR data export (Article 15: Right of access)
 */
@Data
@Builder
public class GdprExportDto {

    // Export metadata
    private Instant exportDate;
    private String exportedBy;

    // Personal data
    private UserDataDto user;
    private List<ActivityDataDto> activities;
    private List<ProgramDataDto> programs;
    private List<MessageDataDto> messages;
    private List<ReviewDataDto> reviews;
    private List<RecommendationDataDto> recommendations;
    private List<ProgressionDataDto> progressions;
    private List<NotificationDataDto> notifications;
    private List<AuditLogDataDto> auditLogs;

    // Metadata
    private Map<String, Long> statistics;

    @Data
    @Builder
    public static class UserDataDto {
        private String id;
        private String email;
        private String nom;
        private String prenom;
        private String bio;
        private String avatarUrl;
        private LocationDto location;
        private Instant createdAt;
        private Instant lastActiveAt;
        private String accountStatus;
    }

    @Data
    @Builder
    public static class LocationDto {
        private Double latitude;
        private Double longitude;
        private Integer blurRadiusM;
    }

    @Data
    @Builder
    public static class ActivityDataDto {
        private String id;
        private String activityName;
        private String categoryName;
        private String level;
        private String format;
        private Boolean visibleOnMap;
        private Instant addedAt;
    }

    @Data
    @Builder
    public static class ProgramDataDto {
        private String id;
        private String titre;
        private String description;
        private String activityName;
        private String status;
        private List<ScheduleDto> schedules;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class ScheduleDto {
        private String id;
        private String jourSemaine;
        private String heureDebut;
        private String heureFin;
        private LocationDto location;
    }

    @Data
    @Builder
    public static class MessageDataDto {
        private String id;
        private String conversationId;
        private String content;
        private Boolean sentByMe;
        private Instant sentAt;
    }

    @Data
    @Builder
    public static class ReviewDataDto {
        private String id;
        private String programTitle;
        private Integer rating;
        private String comment;
        private Map<String, Integer> criteriaScores;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class RecommendationDataDto {
        private String id;
        private String recommendedUserName;
        private String comment;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class ProgressionDataDto {
        private String id;
        private String programTitle;
        private String label;
        private Object value;
        private Instant recordedAt;
    }

    @Data
    @Builder
    public static class NotificationDataDto {
        private String id;
        private String type;
        private String title;
        private String message;
        private Boolean read;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class AuditLogDataDto {
        private String id;
        private String actionType;
        private String entityType;
        private String ipAddress;
        private Instant timestamp;
    }
}
