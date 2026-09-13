package org.program.pair.domain.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Une activité de son propre profil, dans {@code UserPrivateDto}. Renommé de
 * {@code UserActivityDto} (P-BA-17), homonyme de celui de {@code domain.activity}
 * à la forme différente ; {@code UserActivitySummaryDto}, que la fiche proposait,
 * est déjà pris par la vue publique.
 */
public record UserProfileActivityDto(
    UUID id,
    String activityName,
    Boolean visibleOnMap,
    String customDescription,
    String level,
    String format,
    Instant createdAt
) {}
