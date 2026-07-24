package org.program.pair.domain.attendance.dto;

import java.util.UUID;

public record ActivityBreakdownDto(
    UUID activityId,
    String activityName,
    int attendanceCount
) {}
