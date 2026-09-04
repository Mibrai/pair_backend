package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Les créneaux d'un <b>rectangle</b> — la géométrie d'un écran de carte.
 *
 * <p>Les quatre bornes, {@code limit} et {@code offset} reprennent nom pour nom
 * ceux de {@code MapBoundsRequest}, et les filtres reprennent nom pour nom ceux
 * de {@link SlotFeedRequest}. C'était la demande : que les deux onglets de la
 * carte partagent la même géométrie sans que le client ait deux vocabulaires à
 * porter.
 *
 * <p><b>Il n'y a pas de centre, donc pas de rayon et pas de distance.</b> Un
 * rectangle n'en a pas besoin, et {@code SlotFeedItemDto.distanceMeters} est
 * rendu nul par cette route. {@code /slots/feed} garde la sienne : « autour de
 * moi, à telle distance » et « ce que montre un écran » sont deux questions
 * différentes, et son plafond de 50 km est juste pour la première.
 */
public record SlotBoundsRequest(

    @NotNull Double north,
    @NotNull Double south,
    @NotNull Double east,
    @NotNull Double west,

    @Schema(description = "Filtre d'activité, comme sur /slots/feed.")
    UUID activityId,

    @Schema(description = "Filtre de catégorie, valeur unique. Équivaut à categoryIds à un "
        + "élément, et se cumule avec lui (union, pas intersection).")
    UUID categoryId,

    @Schema(description = "Filtre de catégorie, plusieurs valeurs — la multi-sélection des "
        + "filtres de la carte. Répétable (?categoryIds=a&categoryIds=b) ou séparé par des "
        + "virgules.")
    List<UUID> categoryIds,

    @Schema(description = "Début de la fenêtre de recherche. Défaut : maintenant.")
    Instant from,

    @Schema(description = "Fin de la fenêtre de recherche. Défaut : maintenant + 7 jours — "
        + "le même que /slots/feed. Un écran de carte qui veut un horizon plus large doit "
        + "le demander, il ne l'obtient pas en dézoomant.")
    Instant to,

    @Schema(description = "Ne retenir que les créneaux publiés depuis cet instant (UTC). "
        + "Le filtre « Nouveautés », identique à celui du fil.")
    Instant createdSince,

    @Schema(description = "Filtre de langue, permissif : un créneau qui n'en déclare aucune "
        + "n'est jamais exclu. Identique à celui du fil.")
    List<String> languages,

    @Schema(description = "Filtre d'accueil, restrictif : le créneau doit déclarer TOUTES "
        + "les étiquettes demandées. Identique à celui du fil.")
    List<String> accessibilityTags,

    @Schema(description = "Nombre maximum de créneaux rendus. Défaut 100, plafond 200. "
        + "Au-delà, la réponse est un 400 et non un écrêtage silencieux : ce lot est né "
        + "d'une borne rabotée sans le dire, il ne va pas en réintroduire une. Le plafond "
        + "n'est pas arbitraire — chaque organisateur distinct rendu coûte le chargement "
        + "de son profil public, et c'est lui qui gouverne le temps de réponse.",
        defaultValue = "100")
    @Min(1) @Max(MAX_LIMIT) Integer limit,

    @Schema(description = "Décalage de pagination, comme sur /map/bounds.", defaultValue = "0")
    @Min(0) Integer offset
) {

    /** Plafond de {@code limit}. Voir la description du champ pour le pourquoi. */
    public static final int MAX_LIMIT = 200;

    public SlotBoundsRequest {
        if (limit == null) limit = 100;
        if (offset == null) offset = 0;
    }

    /** Les catégories demandées, {@code categoryId} et {@code categoryIds} réunis. */
    public Set<UUID> effectiveCategoryIds() {
        return SlotFilters.categoryIds(categoryId, categoryIds);
    }

    /** Les langues demandées, normalisées. */
    public Set<String> effectiveLanguages() {
        return SlotFilters.languages(languages);
    }

    /** Les étiquettes d'accueil demandées, normalisées. */
    public Set<String> effectiveAccessibilityTags() {
        return SlotFilters.accessibilityTags(accessibilityTags);
    }
}
