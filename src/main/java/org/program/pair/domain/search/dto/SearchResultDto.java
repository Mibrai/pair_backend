package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record SearchResultDto(
    // — commun user / program / slot —
    @Schema(allowableValues = {"user", "program", "slot"})
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
    /** Constructeur court pour les résultats de type "user" (champs program/slot à null). */
    public static SearchResultDto forUser(
            UUID id, String displayName, String avatarUrl,
            Double lat, Double lng, Double distanceMeters,
            String activityName, String level, String format,
            boolean isOnline, String verificationStatus) {
        return new SearchResultDto(
            "user", id, displayName, null, avatarUrl,
            lat, lng, distanceMeters, 0f,
            activityName, level, format, isOnline, verificationStatus,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null
        );
    }
}
