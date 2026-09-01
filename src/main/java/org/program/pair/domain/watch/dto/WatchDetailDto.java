package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Une veille et sa chronologie — ce que rend {@code GET /watches/{id}}.
 */
@Schema(description = "Une veille avec sa chronologie et l'état de remise de ses alertes.")
public record WatchDetailDto(

    WatchDto watch,

    @Schema(description = "Les faits qui jalonnent la veille, dans l'ordre chronologique.")
    List<WatchEventDto> timeline,

    @Schema(description = "État de remise des alertes : NONE (aucune), PENDING, SENT, FAILED. "
        + "Avec un seul canal actif, ce retour dit si le proche a bien été joint.")
    String alertDelivery
) {}
