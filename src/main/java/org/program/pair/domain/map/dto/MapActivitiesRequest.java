package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    @Schema(description = "Filtre de catégorie, plusieurs valeurs. Répétable "
        + "(?categoryIds=a&categoryIds=b) ou séparé par des virgules. Un marqueur est "
        + "retenu si la catégorie de son activité figure dans la liste. Appliqué en base, "
        + "comme sur GET /api/map/bounds : les deux requêtes de la famille /map se "
        + "comportent désormais pareil. Absent ou vide, aucun filtre de catégorie.")
    List<UUID> categoryIds,

    @Schema(description = "Niveau de zoom de la carte (1-20). Quand il est fourni, les "
        + "marqueurs proches sont agrégés en clusters : une cellule de grille portant "
        + "au moins deux marqueurs devient un cluster, une cellule seule reste un "
        + "marqueur d'activité. Plus le zoom est élevé, plus la maille est fine, donc "
        + "moins il y a de clusters. Absent, aucune agrégation n'a lieu et le champ "
        + "clusters de la réponse est vide.", minimum = "1", maximum = "20")
    Integer zoom,

    @Schema(description = "Sortie de secours : rétablit la population d'avant le filtrage "
        + "par séance à venir. Par défaut (absent ou false), la route ne renvoie que les "
        + "activités ayant au moins une séance à venir — marqueurs isolés ET membres des "
        + "clusters, sur la même définition, de sorte que le count d'une pastille soit le "
        + "nombre de marqueurs réellement affichables. Mettre true redonne les activités "
        + "expirées, y compris dans les clusters et dans totalInBounds.",
        defaultValue = "false")
    Boolean includeExpired
) {

    /**
     * Le filtre « séance à venir » s'applique-t-il ?
     *
     * <p>Il est le <b>défaut</b> : aucun écran de l'app ne veut d'une activité
     * sans séance à venir, et un paramètre que le client poserait sur tous ses
     * appels ne serait qu'un défaut écrit deux fois. Comme la route est publique
     * ({@code permitAll}), un consommateur que nous ne connaissons pas peut
     * dépendre de l'ancienne population : {@code includeExpired=true} la lui
     * rend, sans que nous ayons à redéployer quoi que ce soit.
     */
    public boolean filterToUpcoming() {
        return !Boolean.TRUE.equals(includeExpired);
    }

    /** Vrai si un filtre géographique quelconque a été demandé. */
    public boolean hasGeoFilter() {
        return radiusMeters != null || hasBounds();
    }

    public boolean hasBounds() {
        return north != null || south != null || east != null || west != null;
    }

    /** Catégories effectivement demandées, dédoublonnées ; vide si aucun filtre. */
    public Set<UUID> effectiveCategoryIds() {
        if (categoryIds == null) {
            return Set.of();
        }
        return categoryIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
