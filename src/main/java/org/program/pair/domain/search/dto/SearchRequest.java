package org.program.pair.domain.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Recherche en langage naturel, paginée. "
    + "filters/locale/sort_by/sort_order restent ignorés s'ils sont envoyés — "
    + "la langue passe désormais par l'en-tête Accept-Language.")
public record SearchRequest(
    @NotBlank(message = "La requête de recherche est requise")
    @Size(max = 500, message = "La requête ne peut pas dépasser 500 caractères")
    String query,

    @NotNull(message = "La latitude est requise")
    Double lat,

    @NotNull(message = "La longitude est requise")
    Double lng,

    Integer radiusMeters,  // override du rayon détecté par le LLM

    @Schema(description = "Page indexée à 0. Absente : 0.", defaultValue = "0")
    Integer page,

    @Schema(description = "Taille de page. Absente : 20, la taille que la route "
        + "renvoyait avant d'être paginée. Plafonnée à 100.", defaultValue = "20")
    Integer pageSize
) {
    /** Constructeur court, pour les appelants qui ne paginent pas. */
    public SearchRequest(String query, Double lat, Double lng, Integer radiusMeters) {
        this(query, lat, lng, radiusMeters, null, null);
    }

    public int effectivePage() {
        return page != null ? page : 0;
    }

    public int effectivePageSize() {
        return pageSize != null ? pageSize : 20;
    }
}
