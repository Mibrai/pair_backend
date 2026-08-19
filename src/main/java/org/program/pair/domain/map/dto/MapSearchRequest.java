package org.program.pair.domain.map.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MapSearchRequest(
    @NotNull Double lat,
    @NotNull Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,
    UUID activityId,
    String level,
    String format,

    // Ne retenir que les personnes déclarant au moins une de ces langues.
    // Répétable ou séparé par des virgules.
    //
    // Note pour qui ajouterait un filtre ici : level et format sont acceptés et
    // validés depuis longtemps sans être lus nulle part. Celui-ci l'est.
    java.util.List<String> languages
) {

    /** Étiquettes normalisées, sans doublon. Vide quand aucun filtre n'est demandé. */
    public java.util.Set<String> effectiveLanguages() {
        if (languages == null) {
            return java.util.Set.of();
        }
        return languages.stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(value -> java.util.Arrays.stream(value.split(",")))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
