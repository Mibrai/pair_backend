package org.program.pair.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection d'une ligne de {@code GET /api/activities/browse}.
 *
 * <p>Les noms suivent les alias de la requête native de
 * {@code UserActivityRepository.browse(...)} : les renommer d'un côté sans
 * l'autre casse le mapping silencieusement.
 */
public interface ActivityBrowseRow {

    UUID getUserActivityId();
    UUID getActivityId();
    String getActivityName();
    String getActivityIcon();
    String getImageUrl();
    String getDescription();

    UUID getCategoryId();
    String getCategoryName();
    String getCategoryIcon();

    Double getLat();
    Double getLng();
    String getAddress();
    Double getDistanceMeters();
    String getLocationType();

    UUID getOrganizerId();
    String getOrganizerName();
    String getOrganizerAvatarUrl();

    int getProgramCount();
    int getTotalParticipants();

    Instant getNextSessionAt();
    boolean getIsExpired();
}
