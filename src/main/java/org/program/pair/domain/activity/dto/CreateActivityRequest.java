package org.program.pair.domain.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateActivityRequest(
    @NotBlank @Size(min = 2, max = 120) String name,
    @NotNull UUID categoryId
) {}
