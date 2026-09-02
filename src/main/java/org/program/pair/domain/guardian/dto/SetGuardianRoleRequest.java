package org.program.pair.domain.guardian.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.program.pair.domain.guardian.GuardianRole;

/** Le rôle qu'on veut poser sur un contact d'urgence. */
@Schema(description = "Rôle à poser sur un contact d'urgence.")
public record SetGuardianRoleRequest(

    @Schema(description = "PRIMARY, BACKUP ou NONE. Poser un rôle le retire au contact qui "
        + "le portait ; NONE le libère sans rien poser ailleurs.")
    @NotNull(message = "Le rôle est obligatoire.")
    GuardianRole role
) {}
