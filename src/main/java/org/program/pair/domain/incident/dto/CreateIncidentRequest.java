package org.program.pair.domain.incident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.program.pair.domain.incident.IncidentTarget;
import org.program.pair.domain.report.ReportReason;

import java.util.UUID;

/**
 * Signaler un incident de sécurité.
 *
 * <p>La cible décide de la suite. {@link IncidentTarget#PERSON} bascule en plus
 * dans le flux de signalement (modération) — elle exige alors {@code targetUserId}
 * et une description. Les autres cibles restent dans le registre des incidents,
 * séparé de la modération : mêler « perdu en chemin » à « comportement
 * inapproprié » mettrait la victime dans la colonne des signalés.
 */
@Schema(description = "Signalement d'un incident de sécurité.")
public record CreateIncidentRequest(

    @Schema(description = "Ce que vise l'incident : PERSON, PLACE, ORGANISATION, TRANSIT ou SELF.")
    @NotNull(message = "La cible de l'incident est requise.")
    IncidentTarget target,

    @Schema(description = "Description. Requise (10 à 500) pour une cible PERSON, facultative sinon.")
    @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères.")
    String note,

    @Schema(description = "Le créneau concerné, s'il y en a un.")
    UUID scheduleId,

    @Schema(description = "L'utilisateur visé — requis pour une cible PERSON, ignoré sinon.")
    UUID targetUserId,

    @Schema(description = "Motif de signalement, pour une cible PERSON. Défaut : OTHER.")
    ReportReason reason,

    @Schema(description = "URL d'une pièce jointe déjà téléversée via /api/media, facultative.")
    @Size(max = 500)
    String attachmentUrl
) {}
