package org.program.pair.domain.attendance.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingAttendanceDto(
    UUID scheduleId,
    String programTitle,
    String placeName,
    Instant startsAt,
    Instant endsAt
) {}
