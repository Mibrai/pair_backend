package org.program.pair.domain.program.dto;

import org.program.pair.domain.program.UserProgramStatus;

import java.time.Instant;
import java.util.UUID;

public record UserProgramDto(
    UUID id,
    UUID userId,
    UUID programId,
    String programTitle,
    UUID scheduleId,
    UserProgramStatus status,
    String leaveReason,
    Integer progressPercentage,
    Integer activitiesCompleted,
    Integer activitiesSkipped,
    Instant lastActivityAt,
    Instant joinedAt,
    Instant leftAt
) {
}
