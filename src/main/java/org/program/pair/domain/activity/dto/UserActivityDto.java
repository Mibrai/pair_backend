package org.program.pair.domain.activity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserActivityDto(
    UUID id,
    ActivityDto activity,
    Boolean visibleOnMap,
    String customDescription,
    String level,
    String format,
    Instant createdAt,
    List<ProgramSummaryDto> programs
) {}
