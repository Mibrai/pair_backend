package org.program.pair.domain.activity.dto;

import java.time.Instant;
import java.util.UUID;

public record ProgramSummaryDto(
    UUID id,
    String title,
    String status,
    Boolean isPublic,
    Instant updatedAt
) {}
