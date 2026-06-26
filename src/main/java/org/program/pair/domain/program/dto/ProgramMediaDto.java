package org.program.pair.domain.program.dto;

import java.util.UUID;

public record ProgramMediaDto(
    UUID id,
    String url,
    String mediaType,
    Integer displayOrder
) {}
