package org.program.pair.domain.map.dto;

import java.util.UUID;

public record MapActivityDto(
    UUID id,
    String name,
    String slug,
    String description,
    String categoryName,
    String categoryColorRamp,
    double lat,
    double lng
) {}
