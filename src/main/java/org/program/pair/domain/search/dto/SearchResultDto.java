package org.program.pair.domain.search.dto;

import java.time.Instant;
import java.util.UUID;

public record SearchResultDto(
    // — commun user / program —
    String resultType,          // "user" | "program"
    UUID id,
    String title,
    String description,
    String avatarUrl,
    Double lat,
    Double lng,
    Double distanceMeters,
    Float relevanceScore,
    String activityName,
    String level,
    String format,
    boolean isOnline,
    String verificationStatus,

    // — spécifique program —
    UUID userActivityId,
    UUID categoryId,
    String categoryName,
    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    String thumbnailUrl,
    Float averageScore,
    Integer reviewCount,
    Integer enrolledCount,
    String status,
    String locationType,
    String city,
    Instant createdAt,
    Instant updatedAt
) {
    /** Constructeur court pour les résultats de type "user" (champs program à null). */
    public static SearchResultDto forUser(
            UUID id, String displayName, String avatarUrl,
            Double lat, Double lng, Double distanceMeters,
            String activityName, String level, String format,
            boolean isOnline, String verificationStatus) {
        return new SearchResultDto(
            "user", id, displayName, null, avatarUrl,
            lat, lng, distanceMeters, 0f,
            activityName, level, format, isOnline, verificationStatus,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        );
    }
}
