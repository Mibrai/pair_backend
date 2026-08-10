package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Marqueur d'activité de la carte.
 *
 * <p><b>Maille d'agrégation.</b> Un marqueur ne représente pas une activité mais
 * un couple <b>(activité, lieu)</b>. Les créneaux d'une même activité sont
 * regroupés sur la clé {@code (activityId, lat arrondie, lng arrondie)}, avec :
 *
 * <ul>
 *   <li><b>3 décimales</b> — soit ~111 m en latitude ;</li>
 *   <li>arrondi <b>au plus proche, demis vers +∞</b> ({@code Math.round(v * 1000.0) / 1000.0},
 *       c'est-à-dire {@code floor(v * 1000 + 0.5) / 1000}).</li>
 * </ul>
 *
 * <p>Une activité déclarée sur cinq lieux produit donc cinq marqueurs, portant
 * tous le même {@code activityId}. Un client qui déduplique doit le faire sur la
 * clé complète, pas sur le seul {@code activityId} — sinon il perd des lieux.
 *
 * <p><b>Piège de portage.</b> « Demis vers +∞ » n'est pas « demis à l'opposé de
 * zéro » : pour {@code lng = -2.3465}, la règle ci-dessus donne {@code -2.346},
 * là où un arrondi à l'opposé de zéro (celui de {@code num.round()} en Dart, de
 * {@code round()} en Python) donnerait {@code -2.347}. Les deux ne divergent
 * qu'aux demis exacts, mais ils divergent — en longitude occidentale comme en
 * latitude australe. Reproduire la clé côté client demande la règle exacte, pas
 * une équivalente.
 *
 * <p>{@code lat} et {@code lng} du marqueur sont les coordonnées <b>exactes</b>
 * d'un des créneaux du groupe, pas la valeur arrondie : les arrondir avec la
 * règle ci-dessus redonne bien la clé du groupe.
 */
public record MapActivityMarkerDto(
    UUID activityId,
    String activityName,
    String activitySlug,

    @Schema(description = "Catégorie de l'activité. Le marqueur portait déjà son nom, son "
        + "icône et sa teinte, mais pas son identifiant : filtrer par catégorie obligeait à "
        + "comparer des noms — fragile au renommage, et sensible à la langue de service. "
        + "Nul seulement si l'activité n'est rattachée à aucune catégorie.")
    UUID categoryId,

    String categoryName,
    String categoryIcon,
    String categoryColorRamp,

    @Schema(description = "Latitude exacte d'un créneau du groupe. Voir la maille "
        + "d'agrégation dans la description du schéma.")
    double lat,

    @Schema(description = "Longitude exacte d'un créneau du groupe.")
    double lng,

    @Schema(description = "Distance en kilomètres depuis userLat/userLng, nulle si la "
        + "position de l'utilisateur n'a pas été fournie. Noter l'unité : les kilomètres "
        + "ici, les mètres sur POST /search (distanceMeters).")
    Double distanceKm,

    @Schema(description = "Nombre de programmes distincts à ce lieu. Compte bien des "
        + "programmes : un programme unique à trois créneaux hebdomadaires vaut 1, pas 3.")
    int programCount,

    @Schema(description = "Nombre de créneaux à ce lieu, tous programmes confondus. "
        + "Toujours supérieur ou égal à programCount.")
    int scheduleCount,

    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    Instant nextSessionAt,
    String address
) {}
