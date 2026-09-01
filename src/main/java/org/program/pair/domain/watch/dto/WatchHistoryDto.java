package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une veille terminée, telle que le journal la garde.
 *
 * <p><b>Aucune coordonnée.</b> L'archive se contente de l'horodatage et du nom du
 * lieu — jamais la position, jamais l'adresse exacte. Une veille close n'a plus à
 * dire où quelqu'un s'est rendu au mètre près ; le nom du lieu suffit à s'en
 * souvenir, la coordonnée ne ferait qu'un historique de déplacements que rien ne
 * justifie de garder.
 *
 * <p>{@code alertSent} permet à l'écran « Mon journal » de compter les alertes
 * sans deviner : une veille dont le lien public est né a vu une alerte partir.
 */
@Schema(description = "Une veille terminée, pour le journal — sans aucune coordonnée.")
public record WatchHistoryDto(
    UUID id,
    String state,
    String activityName,
    String placeName,
    String city,
    Instant occurrenceStartsAt,
    Instant closedAt,
    @Schema(description = "Vrai si une alerte est partie pendant cette veille.")
    boolean alertSent
) {}
