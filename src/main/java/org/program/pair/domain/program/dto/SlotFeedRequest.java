package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.Instant;
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
    List<String> languages,

    @Schema(description = "Ne retenir que les créneaux qui déclarent TOUTES ces "
        + "étiquettes d'accueil. Restrictif, à l'inverse du filtre de langue : une "
        + "étiquette non déclarée veut dire « rien ne permet de l'affirmer », et "
        + "montrer quand même le créneau enverrait quelqu'un vers un lieu dont "
        + "personne n'a garanti l'accueil. Déclaratif : ces étiquettes ne sont jamais "
        + "vérifiées, et l'interface doit le dire.")
    List<String> accessibilityTags
) {

    /** Les étiquettes d'accessibilité demandées, normalisées et sans doublon. */
    public Set<String> effectiveAccessibilityTags() {
        return SlotFilters.accessibilityTags(accessibilityTags);
    }

    /**
     * Les étiquettes de langue demandées, normalisées en minuscules et sans
     * doublon. Vide quand aucun filtre n'est demandé.
     */
    public Set<String> effectiveLanguages() {
        return SlotFilters.languages(languages);
    }

    /**
     * Catégories effectivement demandées : l'union de {@code categoryId} et de
     * {@code categoryIds}, dédoublonnée.
     *
     * @return l'ensemble des catégories, vide quand aucun filtre n'est demandé
     */
    public Set<UUID> effectiveCategoryIds() {
        return SlotFilters.categoryIds(categoryId, categoryIds);
    }
}
