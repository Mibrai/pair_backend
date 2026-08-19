package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record SlotFeedRequest(
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,

    UUID activityId,        // filtre optionnel

    @Schema(description = "Filtre de catégorie, valeur unique. Conservé : les clients "
        + "déployés l'envoient. Équivaut à categoryIds à un élément, et se cumule avec "
        + "lui (union, pas intersection).")
    UUID categoryId,

    @Schema(description = "Filtre de catégorie, plusieurs valeurs — la multi-sélection des "
        + "filtres de la carte. Répétable (?categoryIds=a&categoryIds=b) ou séparé par des "
        + "virgules. Un créneau est retenu si sa catégorie figure dans la liste. Absent ou "
        + "vide, aucun filtre de catégorie ne s'applique.")
    List<UUID> categoryIds,

    Instant from,           // défaut : maintenant
    Instant to,             // défaut : maintenant + 7 jours

    @Schema(description = "Ne retenir que les créneaux publiés depuis cet instant (UTC), "
        + "bornes comprises. C'est le filtre « Nouveautés » : il porte sur la date de "
        + "publication, pas sur celle de la séance. from/to et createdSince répondent à "
        + "deux questions différentes et se cumulent.")
    Instant createdSince,

    @Schema(description = "Ne retenir que les créneaux dont la langue principale figure "
        + "dans la liste. Un créneau qui n'en déclare aucune n'est jamais exclu : la "
        + "plupart n'en déclareront pas, et exclure faute d'information punirait ceux "
        + "qui n'ont rien rempli. Répétable ou séparé par des virgules.")
    List<String> languages
) {

    /**
     * Les étiquettes de langue demandées, normalisées en minuscules et sans
     * doublon. Vide quand aucun filtre n'est demandé.
     */
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

    /**
     * Catégories effectivement demandées : l'union de {@code categoryId} et de
     * {@code categoryIds}, dédoublonnée.
     *
     * <p>Les deux se cumulent plutôt que l'un ne masque l'autre : un client en
     * cours de migration peut envoyer les deux sans qu'une des deux sélections
     * disparaisse silencieusement.
     *
     * @return l'ensemble des catégories, vide quand aucun filtre n'est demandé
     */
    public Set<UUID> effectiveCategoryIds() {
        Set<UUID> effective = new LinkedHashSet<>();
        if (categoryId != null) {
            effective.add(categoryId);
        }
        if (categoryIds != null) {
            categoryIds.stream().filter(java.util.Objects::nonNull).forEach(effective::add);
        }
        return effective;
    }
}
