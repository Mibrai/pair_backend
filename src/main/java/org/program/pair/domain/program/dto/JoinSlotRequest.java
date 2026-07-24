package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.Size;

public record JoinSlotRequest(
    @Size(max = 300) String joinMessage
) {}
