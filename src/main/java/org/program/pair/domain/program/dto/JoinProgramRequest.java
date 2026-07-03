package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record JoinProgramRequest(
    @NotNull UUID programId,
    UUID scheduleId // Optional: specific schedule to join
) {
}
