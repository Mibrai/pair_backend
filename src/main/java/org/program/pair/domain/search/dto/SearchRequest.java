package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Seuls query/lat/lng/radiusMeters sont pris en compte. "
    + "Il n'y a pas de pagination (page/pageSize sont ignorés, pas d'erreur) : "
    + "chaque appel renvoie sa liste complète de résultats en une fois. "
    + "filters/locale/sort_by/sort_order sont également ignorés s'ils sont envoyés.")
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
