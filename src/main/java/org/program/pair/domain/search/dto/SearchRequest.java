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
    java.util.List<String> accessibilityTags,

    @Schema(description = "Garder les programmes terminés dans les résultats.\n\n"
        + "**Défaut `true`, et c'est la seule différence avec le paramètre du même nom sur "
        + "`GET /activities/browse`, dont le défaut est `false`.** La sémantique de la "
        + "valeur est identique — `true` garde, `false` écarte ; c'est le défaut qui "
        + "diffère, parce que le comportement d'origine des deux routes diffère. `browse` "
        + "écartait déjà les entrées expirées avant que le paramètre n'existe ; `/search` "
        + "les rendait déjà, et le client a demandé qu'elle continue : « on veut pouvoir "
        + "retrouver un programme terminé ». Dans les deux cas le défaut reconduit ce que la "
        + "route faisait la veille, donc aucune version publiée ne voit son écran changer "
        + "sous elle.\n\n"
        + "Envoyer `false` est ce qui rend `totalCount` et les compteurs par type exacts "
        + "quand l'interrupteur « Afficher ce qui est terminé » est éteint : le filtre "
        + "s'applique avant la pagination et avant le décompte, jamais après.",
        defaultValue = "true")
    Boolean includeExpired
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
        this(query, lat, lng, radiusMeters, null, null, null, null);
    }

    /** Constructeur de pagination, sans les filtres. */
    public SearchRequest(String query, Double lat, Double lng, Integer radiusMeters,
                         Integer page, Integer pageSize, java.util.List<String> accessibilityTags) {
        this(query, lat, lng, radiusMeters, page, pageSize, accessibilityTags, null);
    }

    public int effectivePage() {
        return page != null ? page : 0;
    }

    public int effectivePageSize() {
        return pageSize != null ? pageSize : 20;
    }

    /** Voir la description du champ pour le pourquoi de ce défaut-là. */
    public boolean effectiveIncludeExpired() {
        return includeExpired == null || includeExpired;
    }
}
