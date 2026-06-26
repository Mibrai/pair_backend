package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProgramRequest(
    @NotNull UUID userActivityId,
    @NotBlank @Size(max = 150) String title,
    @Size(max = 3000) String description,
    Boolean isPublic
) {}
