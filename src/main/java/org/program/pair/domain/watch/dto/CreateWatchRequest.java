package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Armer une veille sur un créneau.
 *
 * <p>{@code deadlineAt} est facultatif : par défaut, la fin du créneau plus une
 * heure. L'utilisateur peut le fixer plus tôt ou plus tard à l'armement — c'est
 * son heure limite de retour, pas celle du créneau. Une fois posée, elle est
 * figée : voir {@code Watch}.
 */
@Schema(description = "Armement d'une veille retour.")
public record CreateWatchRequest(

    @Schema(description = "Le créneau concerné.")
    @NotNull(message = "Le créneau est obligatoire.")
    UUID scheduleId,

    @Schema(description = "Le contact principal — un de vos contacts d'urgence acceptés.")
    @NotNull(message = "Un contact principal est obligatoire.")
    UUID guardianId,

    @Schema(description = "Le contact de secours, facultatif. Doit aussi être un contact accepté.")
    UUID backupGuardianId,

    @Schema(description = "Heure limite de retour. Par défaut : la fin du créneau plus une heure.")
    Instant deadlineAt
) {}
