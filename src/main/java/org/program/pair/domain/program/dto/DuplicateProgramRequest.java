package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corps optionnel de {@code POST /api/programs/{id}/duplicate}.
 */
public record DuplicateProgramRequest(

    @Schema(description = "Titre du nouveau programme. Absent, celui de l'original suffixé "
        + "de « (copie) », tronqué si nécessaire pour tenir dans les 150 caractères.")
    @Size(max = 150)
    String title
) {}
