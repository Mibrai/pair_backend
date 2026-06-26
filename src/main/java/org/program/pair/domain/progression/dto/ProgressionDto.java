package org.program.pair.domain.progression.dto;

import java.time.Instant;
import java.util.UUID;

public record ProgressionDto(
    UUID id,
    UUID programId,
    String programTitle,
    UUID userId,
    String userDisplayName,
    String title,
    String content,
    float[] metrics,
    String[] metricLabels,
    Boolean isPublic,
    Instant createdAt,
    Instant updatedAt
) {}
