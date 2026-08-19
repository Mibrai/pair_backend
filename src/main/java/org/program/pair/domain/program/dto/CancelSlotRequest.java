package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record CancelSlotRequest(

    @Schema(description = "Motif de l'annulation, montré aux participants. Facultatif, "
        + "mais un fait brut sans explication laisse chacun imaginer le pire.")
    @Size(max = 300) String reason
) {}
