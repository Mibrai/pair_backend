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
    Integer pageSize,

    @Schema(description = "Ne retenir que les créneaux déclarant TOUTES ces étiquettes "
        + "d'accueil. Ne porte que sur les résultats de type « slot » : une étiquette "
        + "d'accessibilité décrit une séance et un lieu, pas un programme, et l'appliquer "
        + "aux programmes reviendrait à leur prêter une propriété qu'ils n'ont pas. "
        + "Déclaratif, jamais vérifié.\n\n"
        + "Premier filtre structuré réellement lu par cette route : les champs "
        + "`filters` et `sort_by` qu'un client enverrait restent ignorés.")
    java.util.List<String> accessibilityTags
) {

    /** Étiquettes demandées, normalisées et sans doublon. */
    public java.util.Set<String> effectiveAccessibilityTags() {
        if (accessibilityTags == null) {
            return java.util.Set.of();
        }
        return accessibilityTags.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toUpperCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Constructeur court, pour les appelants qui ne paginent pas. */
    public SearchRequest(String query, Double lat, Double lng, Integer radiusMeters) {
        this(query, lat, lng, radiusMeters, null, null, null);
    }

    public int effectivePage() {
        return page != null ? page : 0;
    }

    public int effectivePageSize() {
        return pageSize != null ? pageSize : 20;
    }
}
