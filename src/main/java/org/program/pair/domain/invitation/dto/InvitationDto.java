package org.program.pair.domain.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Une invitation envoyée, et ce qu'elle est devenue.")
public record InvitationDto(

    String code,
    String url,

    @Schema(description = "Le créneau visé. Nul si le créneau a été supprimé depuis : la "
        + "trace de l'invitation survit, le rendez-vous non.")
    UUID scheduleId,
    String programTitle,
    Instant startsAt,

    Instant createdAt,

    @Schema(description = "Vrai si la personne invitée a rejoint le créneau.")
    boolean accepted,

    @Schema(description = "Vrai si l'invitation a fait venir un nouveau membre. Une "
        + "invitation acceptée par quelqu'un qui était déjà là a marché sans recruter "
        + "personne — les deux sont distingués parce que les confondre fausserait toute "
        + "mesure.")
    boolean broughtNewMember
) {}
