package org.program.pair.domain.subscription.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une personne qui suit l'appelant, et par quel chemin elle est arrivée.
 *
 * <p>Distinct de {@link SubscriptionDto}, qui décrit l'abonnement vu du côté de
 * l'abonné et porte trois paires de champs de cible (auteur, activité,
 * catégorie). Ici la cible est aplatie en une seule paire
 * {@code targetId}/{@code targetName} : l'auteur qui lit cette liste sait déjà
 * que la cible lui appartient, il veut savoir <b>laquelle</b>.
 *
 * <p>Ces deux formes ne partagent pas de socle commun : deux usages ne suffisent
 * pas à dessiner une abstraction juste, et une abstraction fausse coûte plus
 * cher que la duplication qu'elle évite.
 */
public record SubscriberDto(

    @Schema(description = "Identifiant de l'abonné.")
    UUID userId,

    String displayName,
    String avatarUrl,

    @Schema(description = "AUTHOR | USER_ACTIVITY — le chemin par lequel cette personne "
        + "suit l'appelant. CATEGORY n'apparaît jamais ici : voir la description de la "
        + "route.")
    String type,

    @Schema(description = "Activité concernée. Nul quand type vaut AUTHOR : la personne "
        + "suit l'appelant lui-même, pas l'une de ses activités.")
    UUID targetId,

    @Schema(description = "Nom de l'activité concernée. Nul dans les mêmes cas.")
    String targetName,

    @Schema(description = "Date de l'abonnement, ISO 8601 UTC.")
    Instant subscribedAt
) {}
