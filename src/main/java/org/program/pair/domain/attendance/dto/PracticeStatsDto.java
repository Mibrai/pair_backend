package org.program.pair.domain.attendance.dto;

import java.time.Instant;
import java.util.List;

public record PracticeStatsDto(
    int attendanceCount,        // "12 séances"
    int distinctPartnersCount,  // "avec 7 personnes différentes"
    int currentStreakWeeks,     // "5 semaines d'affilée"
    Instant lastAttendanceAt,
    List<ActivityBreakdownDto> byActivity
) {}
