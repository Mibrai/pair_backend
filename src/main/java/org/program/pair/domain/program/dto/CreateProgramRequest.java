package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.*;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.PreferredTime;
import org.program.pair.domain.program.ProgramPrivacy;

import java.util.UUID;

public record CreateProgramRequest(
    @NotNull UUID userActivityId,
    @NotBlank @Size(max = 150) String title,
    @Size(max = 3000) String description,
    Boolean isPublic,
    Boolean allowParticipantMessages,
    @Min(1) @Max(52) Integer durationWeeks,
    @Min(1) @Max(7) Integer sessionsPerWeek,
    @Min(30) @Max(180) Integer sessionDurationMinutes,
    int[] preferredDays,
    PreferredTime preferredTime,
    @Min(2) @Max(100) Integer maxParticipants,
    ProgramPrivacy privacy,
    String goals,
    String prerequisites,
    LocationType locationType
) {}
