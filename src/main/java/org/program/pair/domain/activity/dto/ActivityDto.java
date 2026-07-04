package org.program.pair.domain.activity.dto;

import java.util.UUID;

public record ActivityDto(
    UUID id,
    String name,
    String slug,
    String description,
    String icon,
    UUID parentId,
    CategoryDto category
) {}
