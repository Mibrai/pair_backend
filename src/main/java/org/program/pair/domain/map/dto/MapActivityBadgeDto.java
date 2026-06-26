package org.program.pair.domain.map.dto;

import java.util.UUID;

public record MapActivityBadgeDto(
    UUID activityId,
    String activityName,
    String level,
    String format,
    String categoryColorRamp
) {}
