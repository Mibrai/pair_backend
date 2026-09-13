package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.PreferredTime;
import org.program.pair.domain.program.ProgramPrivacy;
import org.program.pair.domain.program.ProgramStatus;

public record UpdateProgramRequest(
    @Size(max = 150) String title,
    @Size(max = 3000) String description,
    ProgramStatus status,
    Boolean isPublic,
    Boolean allowParticipantMessages,
    @Min(1) @Max(52) Integer durationWeeks,
    @Min(1) @Max(7) Integer sessionsPerWeek,
    @Min(30) @Max(180) Integer sessionDurationMinutes,
    int[] preferredDays,
    PreferredTime preferredTime,
    @Min(2) @Max(100) Integer maxParticipants,
    ProgramPrivacy privacy,
    String goals,
    String prerequisites,
    LocationType locationType,

    @Schema(description = "Des frais sont à prévoir. Absent ou null : inchangé. Passer à "
        + "false efface aussi costNote.")
    Boolean costToShare,

    @Schema(description = "Précision libre sur les frais, 80 caractères au plus. Absente ou "
        + "null : inchangée. Chaîne vide : retirée. Ignorée tant que costToShare (après "
        + "application de cette requête) vaut false.")
    @Size(max = 80) String costNote
) {}
