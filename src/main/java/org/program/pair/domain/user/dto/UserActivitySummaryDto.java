package org.program.pair.domain.user.dto;

import java.util.UUID;

public record UserActivitySummaryDto(
    UUID id,
    String activityName,
    String level,
    String format
) {}
