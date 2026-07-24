package org.program.pair.domain.attendance.dto;

import java.time.Instant;
import java.util.UUID;

public record AttendanceDto(
    UUID id,
    UUID scheduleId,
    Boolean wasPresent,
    Instant attendedAt,
    Instant confirmedAt
) {}
