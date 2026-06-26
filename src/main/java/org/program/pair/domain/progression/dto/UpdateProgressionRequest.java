package org.program.pair.domain.progression.dto;

import jakarta.validation.constraints.Size;

public record UpdateProgressionRequest(
    @Size(max = 150)
    String title,

    @Size(max = 3000)
    String content,

    float[] metrics,

    String[] metricLabels,

    Boolean isPublic
) {}
