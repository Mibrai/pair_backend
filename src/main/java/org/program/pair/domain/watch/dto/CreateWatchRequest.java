package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Armer une veille sur un créneau.
 *
 * <p><b>{@code guardianId} est facultatif depuis le 03/09.</b> « Une veille qui ne
 * prévient personne n'est pas une veille » restait vrai du point de vue de
 * l'alerte, et devenait faux le premier soir : sans contact accepté, le bouton
 * était éteint pour quiconque n'en avait pas encore désigné — c'est-à-dire au
 * moment précis où l'on en a le plus besoin. Ce qui reste sans contact : les
 * relances, le journal, et la validation de présence par l'hôte.
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

    @Schema(description = "Le contact principal — un de vos contacts d'urgence acceptés. "
        + "Facultatif : sans lui, la veille relance et journalise, mais ne préviendra "
        + "personne et se refermera en NO_CONTACT à l'échéance.")
    UUID guardianId,

    @Schema(description = "Le contact de secours, facultatif. Doit aussi être un contact accepté.")
    UUID backupGuardianId,

    @Schema(description = "Heure limite de retour. Par défaut : la fin du créneau plus une heure.")
    Instant deadlineAt
) {}
