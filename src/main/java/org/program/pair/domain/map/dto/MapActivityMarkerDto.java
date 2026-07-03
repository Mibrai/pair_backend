package org.program.pair.domain.map.dto;

import java.util.UUID;

/**
 * Represents an activity marker on the map.
 * Each activity shows its category icon, name, title, and distance from user if available.
 */
public record MapActivityMarkerDto(
    UUID activityId,
    String activityName,
    String activitySlug,
    String categoryName,
    String categoryIcon,
    String categoryColorRamp,
    double lat,
    double lng,
    Double distanceKm,  // null if user location not available
    int programCount    // number of programs at this location
) {}
