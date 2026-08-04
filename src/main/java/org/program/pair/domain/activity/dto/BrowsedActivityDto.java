package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Une carte de l'Explorer : une activité telle qu'une personne la propose.
 *
 * <p><b>La maille est {@code userActivityId}, pas {@code activityId}.</b> Deux
 * organisateurs proposant le même « Yoga » donnent deux entrées, portant le même
 * {@code activityId} et deux {@code userActivityId} distincts. C'est ce qui rend
 * {@code organizerId} non ambigu — et ce qu'aucune jointure par nom d'activité ne
 * pouvait garantir.
 */
public record BrowsedActivityDto(

    @Schema(description = "Identité de l'entrée. C'est la clé de déduplication, "
        + "et l'identifiant à passer aux écrans de détail.")
    UUID userActivityId,

    @Schema(description = "Activité du référentiel. Non unique dans une page : "
        + "plusieurs personnes peuvent proposer la même.")
    UUID activityId,

    String activityName,
    String activityIcon,
    String imageUrl,

    @Schema(description = "Description propre à l'organisateur si elle existe, "
        + "sinon celle du référentiel.")
    String description,

    UUID categoryId,
    String categoryName,
    String categoryIcon,

    @Schema(description = "Latitude du créneau le plus proche du point demandé. "
        + "Nulle quand l'entrée n'a aucun créneau localisé — activité en ligne, ou "
        + "sans programme.")
    Double lat,

    @Schema(description = "Longitude du créneau le plus proche. Nulle dans les mêmes cas.")
    Double lng,

    @Schema(description = "Adresse du créneau le plus proche, selon sa visibilité : "
        + "adresse exacte si le lieu est public ou si l'hôte l'a autorisée, sinon le "
        + "seul nom du lieu.")
    String address,

    @Schema(description = "Distance en MÈTRES depuis lat/lng de la requête. Nulle "
        + "quand l'entrée n'a pas de position. Noter l'unité : des mètres ici, comme "
        + "sur POST /search, alors que GET /map/activities expose des kilomètres.")
    Double distanceMeters,

    @Schema(description = "IN_PERSON | ONLINE | HYBRID, du programme portant le créneau "
        + "le plus proche. Nul quand l'entrée n'a aucun programme actif.")
    String locationType,

    @Schema(description = "Toujours renseigné : une entrée existe parce qu'une personne "
        + "l'a déclarée, même sans aucun programme.")
    UUID organizerId,

    String organizerName,
    String organizerAvatarUrl,

    @Schema(description = "Programmes actifs et publics de cette personne pour cette "
        + "activité. Compte bien des programmes, pas des créneaux.")
    int programCount,

    @Schema(description = "Inscriptions actives, tous programmes de l'entrée confondus.")
    int totalParticipants,

    @Schema(description = "Prochaine séance à venir, tous programmes confondus. ISO 8601 "
        + "UTC. ATTENTION : les récurrences ne sont pas encore développées — c'est le "
        + "prochain starts_at brut, donc un créneau hebdomadaire dont la première séance "
        + "est passée peut remonter null. Sera corrigé par la demande 4.")
    Instant nextSessionAt,

    @Schema(description = "Vrai seulement si l'entrée est datée — au moins un créneau — "
        + "et qu'aucune séance future n'existe. Une entrée sans aucun créneau n'est "
        + "jamais expirée.")
    boolean isExpired,

    @Schema(description = "Présent seulement si includePrograms=true, et borné aux 3 "
        + "prochains programmes par date de prochaine séance. Null sinon.")
    List<BrowsedProgramDto> programs
) {
    /** Le résumé de programme attendu par la page de détail d'une activité. */
    public record BrowsedProgramDto(
        UUID id,
        String title,
        String level,
        int enrolledCount,
        Instant nextSessionAt
    ) {}
}
