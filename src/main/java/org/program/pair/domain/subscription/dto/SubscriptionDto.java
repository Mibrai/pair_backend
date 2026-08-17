package org.program.pair.domain.subscription.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionDto(
    UUID id,
    String type,
    UUID targetAuthorId,
    String targetAuthorName,
    UUID targetUserActivityId,
    String targetActivityName,
    UUID targetCategoryId,
    String targetCategoryName,

    @Schema(description = "ALL | NEW_ONLY | MUTED. Jamais nul : ALL pour les abonnements "
        + "antérieurs au réglage. MUTED ne coupe que l'émission — l'abonnement subsiste, "
        + "et `subscribed` vaut toujours true sur les DTO de cible.",
        example = "ALL")
    String level,

    @Schema(description = "Latitude de la portée géographique. Nulle hors abonnement "
        + "CATEGORY, et nulle sur un abonnement CATEGORY sans contrainte de portée.")
    Double lat,

    @Schema(description = "Longitude de la portée. Nulle dans les mêmes cas.")
    Double lng,

    @Schema(description = "Rayon de la portée, en MÈTRES — comme /search et /slots/feed, "
        + "et non en kilomètres comme /programs. Nul dans les mêmes cas que lat/lng : "
        + "les trois vont ensemble ou pas du tout.")
    Integer radiusMeters,

    Instant createdAt
) {}
