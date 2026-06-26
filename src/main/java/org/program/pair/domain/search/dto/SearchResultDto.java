package org.program.pair.domain.search.dto;

import java.util.UUID;

public record SearchResultDto(
    String resultType,       // "user" | "program"
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
    String verificationStatus
) {}
