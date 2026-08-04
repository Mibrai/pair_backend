package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Paramètres de {@code GET /api/map/activities}.
 *
 * <p>Tous optionnels, et tous additifs : une requête qui n'en fournit aucun se
 * comporte exactement comme avant l'introduction du bornage — aucun filtre
 * géographique, aucune limite. C'est ce que font les clients déployés.
 *
 * <p>Deux bornages coexistent et se cumulent (intersection) quand les deux sont
 * fournis :
 * <ul>
 *   <li><b>rayon</b> — {@code radiusMeters}, en mètres, qui exige
 *       {@code userLat}/{@code userLng} ;</li>
 *   <li><b>bbox</b> — {@code north}/{@code south}/{@code east}/{@code west},
 *       les quatre ou aucune. Ces noms sont ceux déjà employés par
 *       {@code GET /api/map/bounds} et {@code GET /api/map/clusters} : le
 *       domaine carte reste cohérent avec lui-même.</li>
 * </ul>
 *
 * <p>Une bbox à cheval sur l'antiméridien ({@code west > east}) n'est pas
 * supportée et est rejetée.
 */
public record MapActivitiesRequest(

    @Schema(description = "Latitude de l'utilisateur. Sert au calcul de distanceKm, "
        + "et de centre au filtre radiusMeters.")
    Double userLat,

    @Schema(description = "Longitude de l'utilisateur.")
    Double userLng,

    @Schema(description = "Rayon de recherche en mètres autour de userLat/userLng. "
        + "Exige userLat et userLng. Borné entre 1 et 200000 (200 km) ; hors bornes, "
        + "400 MAP_RADIUS_OUT_OF_RANGE.", minimum = "1", maximum = "200000")
    Integer radiusMeters,

    @Schema(description = "Latitude maximale de la bbox. Les quatre bornes vont ensemble.")
    Double north,

    @Schema(description = "Latitude minimale de la bbox.")
    Double south,

    @Schema(description = "Longitude maximale de la bbox.")
    Double east,

    @Schema(description = "Longitude minimale de la bbox.")
    Double west,

    @Schema(description = "Nombre maximal de marqueurs non agrégés renvoyés. Plafonné à "
        + "1000 côté serveur. Quand des marqueurs sont écartés, truncated vaut true et "
        + "totalInBounds donne le total réel.", minimum = "1", maximum = "1000")
    Integer limit,

    @Schema(description = "Niveau de zoom de la carte (1-20). Quand il est fourni, les "
        + "marqueurs proches sont agrégés en clusters : une cellule de grille portant "
        + "au moins deux marqueurs devient un cluster, une cellule seule reste un "
        + "marqueur d'activité. Plus le zoom est élevé, plus la maille est fine, donc "
        + "moins il y a de clusters. Absent, aucune agrégation n'a lieu et le champ "
        + "clusters de la réponse est vide.", minimum = "1", maximum = "20")
    Integer zoom
) {

    /** Vrai si un filtre géographique quelconque a été demandé. */
    public boolean hasGeoFilter() {
        return radiusMeters != null || hasBounds();
    }

    public boolean hasBounds() {
        return north != null || south != null || east != null || west != null;
    }
}
