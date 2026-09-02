package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record SearchResultDto(
    // — commun user / program / slot —
    /**
     * Le type du résultat.
     *
     * <p><b>{@code "user"} a été retiré le 02/09, et c'est une correction de
     * contrat, pas une régression.</b> La valeur était déclarée depuis l'origine, et
     * aucun code n'en produisait : les trois producteurs rendent {@code "slot"} ou
     * {@code "program"}. Une fabrique {@code forUser} existait même, sans appelant.
     * Un client a écrit un onglet « personnes » sur la foi de cette énumération,
     * l'a vu rester vide, et n'a pu le découvrir qu'en interrogeant la production —
     * aucune erreur ne pouvait le signaler. Une valeur qu'aucun code n'émet n'est
     * pas « réservée », elle est fausse ; on la retire plutôt que de la documenter.
     *
     * <p>Le jour où la recherche de personnes existera, elle reviendra avec le code
     * qui la produit — et vraisemblablement sans contrainte géographique : chercher
     * quelqu'un par son nom n'a rien de local.
     */
    @Schema(allowableValues = {"program", "slot"})
    String resultType,
    UUID id,
    String title,
    String description,
    String avatarUrl,

    @Schema(description = "Latitude du résultat. Pour resultType=\"program\", celle de la "
        + "séance localisée la plus proche du point interrogé — jamais celle du compte "
        + "organisateur. Nulle quand le programme n'a aucune séance localisée, ou qu'il se "
        + "tient à distance (locationType REMOTE ou ONLINE) : rien à afficher se dit par "
        + "l'absence, jamais par un repli.")
    Double lat,
    @Schema(description = "Longitude du résultat. Mêmes règles que lat.")
    Double lng,
    @Schema(description = "Distance au point interrogé, en mètres. Nulle exactement quand "
        + "lat/lng le sont : une distance sans lieu n'a pas de sens.")
    Double distanceMeters,
    Float relevanceScore,
    String activityName,
    String level,
    String format,

    @Schema(description = "Présence récente de l'organisateur : actif dans les cinq "
        + "dernières minutes. Ce champ ne dit PAS que le programme se tient à distance — "
        + "cette notion-là est portée par locationType (REMOTE, ONLINE, IN_PERSON, HYBRID). "
        + "Toujours false pour resultType=\"slot\".")
    boolean isOnline,
    String verificationStatus,

    // — spécifique program / slot —
    UUID userActivityId,
    UUID categoryId,
    String categoryName,
    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    String thumbnailUrl,
    Float averageScore,
    Integer reviewCount,
    Integer enrolledCount,
    String status,
    String locationType,
    String city,
    Instant createdAt,
    Instant updatedAt,

    // — spécifique slot (nullable, absent pour user/program) —
    @Schema(description = "Date de début du créneau. Obligatoire pour resultType=\"slot\", absent sinon.")
    Instant startsAt,
    @Schema(description = "Date de fin du créneau, si connue.")
    Instant endsAt,
    @Schema(description = "Capacité du créneau, pour afficher par ex. \"3 / 8\".")
    Integer maxParticipants
) {
}
