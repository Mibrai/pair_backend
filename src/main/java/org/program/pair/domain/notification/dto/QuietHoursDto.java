package org.program.pair.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Les heures pendant lesquelles rien ne sonne. Nulles : aucun "
    + "silence demandé.")
public record QuietHoursDto(

    @Schema(description = "Heure de début, incluse (0–23), dans le fuseau de l'appareil.")
    Integer start,

    @Schema(description = "Heure de fin, exclue (0–23). Peut être inférieure au début : "
        + "« 22 → 7 » décrit une nuit, et c'est le réglage courant.")
    Integer end,

    @Schema(description = "Vrai si une fenêtre est en vigueur.")
    boolean enabled
) {}
