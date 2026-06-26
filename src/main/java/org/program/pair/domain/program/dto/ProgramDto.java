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
    Instant createdAt,
    Instant updatedAt,
    List<ScheduleDto> schedules,
    List<ProgramMediaDto> media,
    Float averageScore,
    Integer reviewCount
) {}
