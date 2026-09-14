package org.program.pair.domain.progression.dto;

import java.util.Map;

public record ProgressionStatsDto(
    int totalProgressions,
    int publicProgressions,
    int privateProgressions,
    Map<String, Object> metricsAggregates
    // Plus de série (streak) depuis le 14/09 : ni série ni stat d'effort (P-MU-25).
) {}
