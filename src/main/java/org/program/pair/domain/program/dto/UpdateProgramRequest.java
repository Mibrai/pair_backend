package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.Size;
import org.program.pair.domain.program.ProgramStatus;

public record UpdateProgramRequest(
    @Size(max = 150) String title,
    @Size(max = 3000) String description,
    ProgramStatus status,
    Boolean isPublic
) {}
