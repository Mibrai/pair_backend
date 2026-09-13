package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.PreferredTime;
import org.program.pair.domain.program.ProgramPrivacy;

import java.util.UUID;

public record CreateProgramRequest(
    @NotNull UUID userActivityId,
    @NotBlank @Size(max = 150) String title,
    @Size(max = 3000) String description,
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

    @Schema(description = "Des frais sont à prévoir (location du terrain, entrée…). "
        + "Absent : false. False ne veut pas dire gratuit, seulement que rien n'est annoncé.")
    Boolean costToShare,

    @Schema(description = "Précision libre sur les frais, 80 caractères au plus. Ignorée "
        + "quand costToShare est false. Jamais un montant structuré.")
    @Size(max = 80) String costNote
) {}
