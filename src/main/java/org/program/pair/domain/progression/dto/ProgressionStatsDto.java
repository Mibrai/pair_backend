package org.program.pair.domain.progression.dto;

import java.util.Map;

public record ProgressionStatsDto(
    int totalProgressions,
    int publicProgressions,
    int privateProgressions,
    Map<String, Object> metricsAggregates,
    StreakDto streak
) {}
