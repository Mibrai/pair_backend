package org.program.pair.domain.publicslot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Ouvre ou ferme le partage public d'un créneau.")
public record SetShareableRequest(

    @Schema(description = "Faux : le lien existant cesse de mener quelque part, sans "
        + "être effacé. Vrai : il redevient valide, le même qu'avant.")
    @NotNull Boolean isPubliclyShareable
) {}
