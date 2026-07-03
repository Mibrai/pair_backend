package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record LeaveProgramRequest(
    @NotNull UUID userProgramId,
    @Size(max = 500, message = "Leave reason must be max 500 characters")
    String reason
) {
}
