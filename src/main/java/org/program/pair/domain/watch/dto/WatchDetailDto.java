package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Une veille et sa chronologie — ce que rend {@code GET /watches/{id}}.
 */
@Schema(description = "Une veille avec sa chronologie.")
public record WatchDetailDto(

    WatchDto watch,

    @Schema(description = "Les faits qui jalonnent la veille, dans l'ordre chronologique.")
    List<WatchEventDto> timeline
) {}
