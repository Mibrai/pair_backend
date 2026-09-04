package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Les créneaux d'un rectangle, et de quoi savoir si on les a tous.
 *
 * <p>Une enveloppe, là où {@code /slots/feed} rend une liste nue. C'est la
 * différence entre un fil et une carte : un fil qui s'arrête à cent éléments se
 * fait défiler, une carte qui s'arrête à cent pins prétend qu'il n'y en a que
 * cent. La demande était explicite — « nous préférons une carte qui dit qu'il y
 * en a plus à une carte qui en cache en silence ».
 */
public record SlotBoundsResponse(

    List<SlotFeedItemDto> slots,

    @Schema(description = "Vrai si des créneaux de la zone n'ont pas été rendus, par "
        + "limit ou par offset. Même sémantique que sur /map/bounds : c'est le drapeau "
        + "du bandeau de troncature.")
    boolean truncated,

    @Schema(description = "Nombre de créneaux de la zone avant application de limit et "
        + "offset. **Exact**, contrairement à son homonyme de /map/bounds : il vient d'un "
        + "COUNT portant sur exactement le même WHERE que la page rendue — mêmes filtres, "
        + "même règle de lieu, même prédicat de blocage. Un total qui compterait ce que la "
        + "page n'a pas le droit de montrer serait une fuite en soi.")
    int totalInBounds
) {}
