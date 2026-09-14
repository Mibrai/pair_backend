package org.program.pair.domain.attendance.dto;

import java.time.Instant;
import java.util.List;

public record PracticeStatsDto(
    int attendanceCount,        // "12 séances"
    int distinctPartnersCount,  // "avec 7 personnes différentes"
    // Plus de currentStreakWeeks depuis le 14/09 (P-MU-25, V126) : ni série ni
    // stat d'effort. DoctrineContratSansDecompteTest tient la porte fermée.
    Instant lastAttendanceAt,
    List<ActivityBreakdownDto> byActivity
) {}
