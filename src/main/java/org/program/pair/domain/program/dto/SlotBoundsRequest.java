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

    @Schema(description = "Début de la fenêtre de recherche, en ISO-8601 UTC — le suffixe "
        + "`Z` est obligatoire, faute de quoi la conversion échoue en 400 VALIDATION_ERROR. "
        + "Défaut : maintenant, ou il y a trois mois quand includePast=true.",
        example = "2026-09-05T19:10:30Z")
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
    @Min(0) Integer offset,

    @Schema(description = "Faire entrer les créneaux déjà terminés dans la réponse — "
        + "l'interrupteur « Afficher ce qui est terminé » de l'onglet Créneaux.\n\n"
        + "Sans lui, `from` dans le passé paraît ignoré : la fenêtre l'honore, mais tout "
        + "créneau terminé est passé au statut `PAST` dans l'heure qui suit sa fin, et le "
        + "filtre de statut les avait déjà tous écartés. `includePast=true` lève ce "
        + "filtre-là, et lui seul : le programme doit toujours être actif et public, l'hôte "
        + "actif, le lieu partagé. Les créneaux **annulés** restent absents dans tous les "
        + "cas — ils n'ont pas eu lieu.\n\n"
        + "La fenêtre est alors plafonnée à " + PAST_WINDOW_DAYS + " jours en arrière : "
        + "un `from` plus ancien est refusé par un 400 SLOT_PAST_WINDOW_TOO_WIDE, jamais "
        + "ramené en silence à la borne. Une carte-souvenir qui remonterait à deux ans est "
        + "un balayage d'historique, pas un écran.",
        defaultValue = "false")
    Boolean includePast
) {

    /** Plafond de {@code limit}. Voir la description du champ pour le pourquoi. */
    public static final int MAX_LIMIT = 200;

    /**
     * Profondeur maximale du passé consultable, en jours.
     *
     * <p>Trois mois : ce que le client a chiffré — « trois mois suffiraient
     * largement à l'usage visé », retrouver où avait lieu un cours du mois
     * dernier. La borne existe parce que la question posée reste celle d'un
     * écran de carte ; au-delà, c'est un historique, et un historique se
     * pagine par date, pas par rectangle.
     */
    public static final int PAST_WINDOW_DAYS = 90;

    public SlotBoundsRequest {
        if (limit == null) limit = 100;
        if (offset == null) offset = 0;
        if (includePast == null) includePast = false;
    }

    /** Jamais nul après le constructeur compact ; ce raccourci évite l'unboxing chez l'appelant. */
    public boolean effectiveIncludePast() {
        return Boolean.TRUE.equals(includePast);
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
