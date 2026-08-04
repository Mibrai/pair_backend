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
        + "application du plafond. Égal à activities.size() quand truncated vaut false.")
    int totalInBounds
) {
    /** Réponse non tronquée — totalInBounds se déduit de la liste. */
    public static MapActivitiesResponse untruncated(List<MapActivityMarkerDto> activities,
                                                     DefaultMapCenter defaultCenter) {
        return new MapActivitiesResponse(activities, defaultCenter, false, activities.size());
    }

    public record DefaultMapCenter(
        double lat,
        double lng,
        int zoom
    ) {}
}
