package org.program.pair.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.program.dto.ScheduleConflictDto;

import java.time.Instant;
import java.util.List;

/**
 * Corps du {@code 409} rendu quand une inscription est refusée pour chevauchement.
 *
 * <p>C'est un {@link ErrorResponse} augmenté d'un tableau : les trois premiers
 * champs portent exactement la même chose qu'un refus ordinaire, de sorte qu'un
 * client qui ne connaît que {@code code}/{@code message} continue de fonctionner
 * et n'affiche « simplement » pas la liste.
 */
public record ScheduleConflictResponse(

    @Schema(description = "Toujours SCHEDULE_CONFLICT.")
    String code,

    @Schema(description = "Phrase destinée à l'utilisateur, traduite selon Accept-Language.")
    String message,

    @Schema(description = "Les chevauchements relevés, du plus proche au plus lointain. "
        + "Jamais vide : sans conflit, l'inscription aurait réussi. Plafonnée à 20 entrées.")
    List<ScheduleConflictDto> conflicts,

    Instant timestamp
) {}
