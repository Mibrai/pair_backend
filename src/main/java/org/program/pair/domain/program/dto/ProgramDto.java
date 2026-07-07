package org.program.pair.domain.program.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgramDto(
    UUID id,
    String title,
    String description,
    String status,
    Boolean isPublic,
    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    String activityName,
    String activityIcon,
    Instant nextSessionAt,
    Instant createdAt,
    Instant updatedAt,
    List<ScheduleDto> schedules,
    List<ProgramMediaDto> media,
    Float averageScore,
    Integer reviewCount,
    Integer enrolledCount,
    // Champs ajoutés par V26
    Integer durationWeeks,
    Integer sessionsPerWeek,
    Integer sessionDurationMinutes,
    int[] preferredDays,
    String preferredTime,
    Integer maxParticipants,
    String privacy,
    String goals,
    String prerequisites,
    String locationType
) {}
