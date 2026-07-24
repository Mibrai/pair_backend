package org.program.pair.domain.search.dto;

import java.util.Map;

public record EmptyStateActionDto(
    String type,        // "EXPAND_RADIUS" | "CREATE_SLOT" | "SET_ALERT" | "SIMILAR_ACTIVITY"
    String label,
    Map<String, Object> payload
) {}
