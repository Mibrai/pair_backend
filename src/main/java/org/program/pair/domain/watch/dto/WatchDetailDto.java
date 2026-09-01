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

    @Schema(description = "État de remise des alertes : NONE (aucune), PENDING, SENT (accepté par "
        + "le fournisseur), DELIVERED (arrivé), BOUNCED (a rebondi / marqué indésirable), "
        + "FAILED (envoi échoué). Avec un seul canal actif, ce retour dit si le proche a été joint.")
    String alertDelivery,

    @Schema(description = "Nombre de retours confirmés d'affilée, celui-ci compris quand il "
        + "l'est. Compte les veilles refermées par le code de la personne, en remontant "
        + "jusqu'à la première qui a mal fini. Une veille désarmée avant le départ ne compte "
        + "ni ne rompt : il n'y avait pas de retour à confirmer. Jamais nul — zéro est zéro.")
    int consecutiveConfirmedReturns
) {}
