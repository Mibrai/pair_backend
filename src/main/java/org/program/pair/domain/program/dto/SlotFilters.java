package org.program.pair.domain.program.dto;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * La normalisation des filtres de créneaux, écrite une fois.
 *
 * <p>Deux requêtes portent désormais les mêmes filtres — {@link SlotFeedRequest}
 * (« autour de moi, à telle distance ») et {@link SlotBoundsRequest} (« dans ce
 * rectangle »). Ce sont deux géométries, pas deux vocabulaires : une catégorie
 * cochée sur la carte doit vouloir dire la même chose dans les deux onglets.
 *
 * <p>Recopier ces trois méthodes garantirait qu'une des deux copies dérive un
 * jour — et la dérive serait silencieuse, puisqu'un filtre qui normalise
 * autrement ne lève rien : il rend simplement un autre résultat.
 */
final class SlotFilters {

    private SlotFilters() {}

    /**
     * L'union de {@code categoryId} et de {@code categoryIds}, dédoublonnée.
     *
     * <p>Les deux se cumulent plutôt que l'un ne masque l'autre : un client en
     * cours de migration peut envoyer les deux sans qu'une des deux sélections
     * disparaisse silencieusement.
     */
    static Set<UUID> categoryIds(UUID categoryId, List<UUID> categoryIds) {
        Set<UUID> effective = new LinkedHashSet<>();
        if (categoryId != null) {
            effective.add(categoryId);
        }
        if (categoryIds != null) {
            categoryIds.stream().filter(Objects::nonNull).forEach(effective::add);
        }
        return effective;
    }

    /** Les langues demandées, en minuscules, sans doublon. */
    static Set<String> languages(List<String> values) {
        return normalize(values, false);
    }

    /** Les étiquettes d'accueil demandées, en majuscules, sans doublon. */
    static Set<String> accessibilityTags(List<String> values) {
        return normalize(values, true);
    }

    /**
     * Le tronc commun des deux listes de chaînes : répétables
     * ({@code ?x=a&x=b}) ou séparées par des virgules, indifféremment. Seule la
     * casse cible les distingue, et elle n'est pas décorative — le SQL compare
     * ces valeurs telles quelles aux colonnes correspondantes.
     */
    private static Set<String> normalize(List<String> values, boolean upperCase) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .map(value -> upperCase
                ? value.toUpperCase(Locale.ROOT)
                : value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
