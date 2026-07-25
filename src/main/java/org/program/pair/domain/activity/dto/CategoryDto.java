package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record CategoryDto(
    UUID id,
    String name,
    String icon,
    @Schema(
        description = "Nom de rampe (ex. \"orange-red\") — jamais un code hexadécimal, "
            + "jamais null. Chaque client résout ce nom dans sa propre palette visuelle.",
        example = "orange-red"
    )
    String colorRamp
) {}
