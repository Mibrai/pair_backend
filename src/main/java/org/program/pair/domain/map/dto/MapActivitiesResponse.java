package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response containing all activity markers and the default center point.
 *
 * <p>{@code truncated} et {@code totalInBounds} sont des champs additifs : ils
 * disent au client qu'il regarde une carte partielle, au lieu de le lui laisser
 * ignorer. L'enveloppe {@code { "activities": [...] }} ne change pas.
 */
public record MapActivitiesResponse(
    List<MapActivityMarkerDto> activities,
    DefaultMapCenter defaultCenter,

    @Schema(description = "Vrai si des marqueurs présents dans la zone demandée ont été "
        + "écartés par le plafond (limit). Le client peut alors le signaler à "
        + "l'utilisateur plutôt qu'afficher silencieusement une carte incomplète.")
    boolean truncated,

    @Schema(description = "Nombre total de marqueurs dans la zone demandée, avant "
        + "agrégation et avant application du plafond. Sans troncature, la somme des "
        + "count des clusters plus la taille d'activities lui est égale.")
    int totalInBounds,

    @Schema(description = "Agrégats de marqueurs proches, quand le paramètre zoom est "
        + "fourni. Vide sinon. Les marqueurs agrégés ici ne figurent pas dans "
        + "activities : les deux listes sont disjointes.")
    List<MapCluster> clusters
) {
    // Une fabrique untruncated(...) vivait ici. Son unique appelant était le
    // repli qui rendait une carte vide en 200 sur défaillance, supprimé avec
    // lui : une fabrique sans appelant suggère un usage qui n'existe pas.

    public record DefaultMapCenter(
        double lat,
        double lng,
        int zoom
    ) {}
}
