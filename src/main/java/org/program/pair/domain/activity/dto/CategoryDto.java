package org.program.pair.domain.activity.dto;

import java.util.UUID;

public record CategoryDto(
    UUID id,
    String name,
    String icon,
    String colorRamp
) {}
