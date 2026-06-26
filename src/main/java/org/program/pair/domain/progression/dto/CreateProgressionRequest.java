package org.program.pair.domain.progression.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProgressionRequest(
    @NotNull UUID programId,

    @NotBlank
    @Size(max = 150)
    String title,

    @Size(max = 3000)
    String content,

    float[] metrics,

    String[] metricLabels,

    Boolean isPublic
) {
    public CreateProgressionRequest {
        if (isPublic == null) {
            isPublic = false;
        }
    }
}
