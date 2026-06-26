package org.program.pair.domain.progression.dto;

import java.time.LocalDate;
import java.util.List;

public record StreakDto(
    int currentStreak,
    int longestStreak,
    LocalDate lastProgressionDate,
    int totalProgressions,
    List<LocalDate> activeDates
) {}
