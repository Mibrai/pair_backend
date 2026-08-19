package org.program.pair.domain.block.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record BlockRequest(

    @Schema(description = "Motif facultatif, à usage de modération. N'est jamais montré "
        + "à la personne bloquée — le blocage doit rester indétectable.")
    @Size(max = 30) String reason
) {}
