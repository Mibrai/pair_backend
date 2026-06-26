package org.program.pair.shared.dto;

import java.time.Instant;

public record ErrorResponse(
    String code,
    String message,
    Instant timestamp
) {}
