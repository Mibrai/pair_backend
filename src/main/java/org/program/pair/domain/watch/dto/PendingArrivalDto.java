package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Un inscrit qu'un organisateur attend encore — un nom, une absence, une heure.
 *
 * <p><b>Trois champs, et pas un de plus.</b> C'est la décision 15 : l'organisateur
 * voit qui n'est pas arrivé, depuis quand, et peut dire « je la vois » — jamais une
 * position, un motif, un contact d'urgence, ni un retour. Ce type est fermé pour ne
 * pas pouvoir en accueillir davantage : y ajouter un champ serait donner à
 * l'organisateur une information de traçabilité qui ne le regarde pas.
 *
 * <p>{@code name} est le prénom réduit de l'inscrit ; {@code since} est le début de
 * la séance, l'heure à laquelle on l'attendait.
 */
@Schema(description = "Un inscrit dont l'arrivée n'est pas encore validée.")
public record PendingArrivalDto(
    UUID watchId,
    String name,
    Instant since
) {}
