package org.program.pair.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Règle ou retire les heures de silence. Les deux bornes vont "
    + "ensemble : les envoyer toutes deux nulles retire le silence, n'en envoyer "
    + "qu'une est refusé — une fenêtre à moitié définie ne décrit rien, et deviner "
    + "l'autre borne ferait taire des notifications sur une intention supposée.")
public record UpdateQuietHoursRequest(

    @Min(value = 0, message = "L'heure de début doit être comprise entre 0 et 23.")
    @Max(value = 23, message = "L'heure de début doit être comprise entre 0 et 23.")
    Integer start,

    @Min(value = 0, message = "L'heure de fin doit être comprise entre 0 et 23.")
    @Max(value = 23, message = "L'heure de fin doit être comprise entre 0 et 23.")
    Integer end
) {}
