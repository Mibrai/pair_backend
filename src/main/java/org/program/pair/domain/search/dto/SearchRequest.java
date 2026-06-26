package org.program.pair.domain.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SearchRequest(
    @NotBlank(message = "La requête de recherche est requise")
    @Size(max = 500, message = "La requête ne peut pas dépasser 500 caractères")
    String query,

    @NotNull(message = "La latitude est requise")
    Double lat,

    @NotNull(message = "La longitude est requise")
    Double lng,

    Integer radiusMeters  // override du rayon détecté par le LLM
) {}
