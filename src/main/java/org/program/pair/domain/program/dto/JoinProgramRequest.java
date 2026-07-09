package org.program.pair.domain.program.dto;

import java.util.UUID;

public record JoinProgramRequest(
    UUID scheduleId
) {
}
