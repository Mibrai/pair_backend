package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.watch.WatchEvent;
import org.program.pair.domain.watch.WatchEventType;

import java.time.Instant;

/**
 * Une ligne de la chronologie d'une veille.
 */
@Schema(description = "Un fait de la chronologie d'une veille.")
public record WatchEventDto(

    WatchEventType type,
    Instant occurredAt,

    @Schema(description = "Précision technique optionnelle, jamais un texte libre.")
    String detail
) {

    public static WatchEventDto from(WatchEvent e) {
        return new WatchEventDto(e.getType(), e.getOccurredAt(), e.getDetail());
    }
}
