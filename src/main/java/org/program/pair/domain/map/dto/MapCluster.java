package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Agrégat de marqueurs proches, pour une carte qui n'a pas à tous les dessiner.
 *
 * <p>Les quatre bornes décrivent l'<b>étendue réelle des membres</b>, pas la
 * cellule de grille qui les a regroupés : c'est ce dont le client a besoin pour
 * recadrer sur le cluster au tap. Recadrer sur la cellule laisserait des marges
 * vides, ou couperait un membre posé sur son bord.
 *
 * <p>Un cluster d'un seul membre est dégénéré — ses bornes valent son propre
 * point. Le clustering d'activités n'en émet pas : un marqueur seul dans sa
 * cellule est renvoyé tel quel, non agrégé.
 */
public record MapCluster(
    Double latitude,
    Double longitude,
    Integer count,
    String type,

    @Schema(description = "Latitude minimale des membres du cluster.")
    Double boundsSouth,
    @Schema(description = "Latitude maximale des membres du cluster.")
    Double boundsNorth,
    @Schema(description = "Longitude minimale des membres du cluster.")
    Double boundsWest,
    @Schema(description = "Longitude maximale des membres du cluster.")
    Double boundsEast,

    @Schema(description = "Icône de la catégorie dominante parmi les membres, quand "
        + "elle a un sens. Nulle pour un cluster d'utilisateurs.")
    String categoryIcon,

    @Schema(description = "Identifiants des activités agrégées, dédoublonnés, dans l'ordre "
        + "où elles apparaissent. Nul pour un cluster d'utilisateurs, qui n'agrège pas des "
        + "activités. Au zoom maximal la maille vaut encore ~1 km : deux activités plus "
        + "proches restent groupées, et sans cette liste taper la pastille ne pouvait rien "
        + "produire. La taille de la liste peut être inférieure à count — un même "
        + "activityId apparaît autant de fois qu'elle a de lieux dans la cellule.")
    List<UUID> activityIds
) {

    /**
     * Cluster construit à partir des coordonnées de ses membres : centre au
     * barycentre, bornes à l'étendue réelle.
     *
     * @param latLngs couples {@code {lat, lng}}, au moins un
     */
    public static MapCluster of(List<double[]> latLngs, String type, String categoryIcon) {
        return of(latLngs, type, categoryIcon, null);
    }

    /**
     * Même chose, en emportant les identifiants des membres — ce qui permet au
     * client d'ouvrir le contenu du groupe sans un aller-retour de rattrapage
     * autour de son centre.
     */
    public static MapCluster of(List<double[]> latLngs, String type, String categoryIcon,
                                List<UUID> activityIds) {
        double avgLat = latLngs.stream().mapToDouble(p -> p[0]).average().orElse(0.0);
        double avgLng = latLngs.stream().mapToDouble(p -> p[1]).average().orElse(0.0);

        return new MapCluster(
            round(avgLat), round(avgLng), latLngs.size(), type,
            round(latLngs.stream().mapToDouble(p -> p[0]).min().orElse(avgLat)),
            round(latLngs.stream().mapToDouble(p -> p[0]).max().orElse(avgLat)),
            round(latLngs.stream().mapToDouble(p -> p[1]).min().orElse(avgLng)),
            round(latLngs.stream().mapToDouble(p -> p[1]).max().orElse(avgLng)),
            categoryIcon,
            activityIds == null ? null : List.copyOf(activityIds)
        );
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
