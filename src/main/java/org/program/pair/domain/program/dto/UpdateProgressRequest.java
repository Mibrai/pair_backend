package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateProgressRequest(
    @NotNull UUID userProgramId,
    @Min(0) @Max(100) Integer progressPercentage
) {
}
