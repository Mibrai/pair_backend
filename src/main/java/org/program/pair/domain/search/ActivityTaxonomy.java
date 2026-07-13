package org.program.pair.domain.search;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Taxonomie canonique des activités, multilingue (EN/DE/FR).
 * Sert de couche déterministe pour garantir le matching cross-lingue sur les
 * activités connues (ex: "Laufen" -> slug "running" -> tous les programmes de
 * course/marche à pied, quelle que soit la langue de stockage), en complément
 * du rappel apporté par les embeddings sémantiques.
 */
@Component
public class ActivityTaxonomy {

    public record CanonicalActivity(String slug, List<String> labels) {}

    private static final List<CanonicalActivity> ENTRIES = List.of(
        new CanonicalActivity("running", List.of(
            "running", "run", "jogging", "jog", "laufen", "lauf",
            "course a pied", "course", "footing", "marche a pied", "marche",
            "walking", "walk", "gehen")),
        new CanonicalActivity("cycling", List.of(
            "cycling", "cycle", "bike", "biking", "radfahren", "rad", "fahrrad",
            "cyclisme", "velo", "vtt")),
        new CanonicalActivity("swimming", List.of(
            "swimming", "swim", "schwimmen", "natation", "nager")),
        new CanonicalActivity("football", List.of(
            "football", "soccer", "fussball", "foot")),
        new CanonicalActivity("basketball", List.of(
            "basketball", "basket", "basketball")),
        new CanonicalActivity("yoga", List.of("yoga")),
        new CanonicalActivity("meditation", List.of(
            "meditation", "meditieren", "mediation", "achtsamkeit", "pleine conscience")),
        new CanonicalActivity("judo", List.of("judo")),
        new CanonicalActivity("karate", List.of("karate", "karaté")),
        new CanonicalActivity("hiking", List.of(
            "hiking", "hike", "wandern", "wanderung", "randonnee", "rando")),
        new CanonicalActivity("tennis", List.of("tennis")),
        new CanonicalActivity("badminton", List.of("badminton", "federball")),
        new CanonicalActivity("handball", List.of("handball")),
        new CanonicalActivity("rugby", List.of("rugby")),
        new CanonicalActivity("crossfit", List.of("crossfit", "cross fit")),
        new CanonicalActivity("strength_training", List.of(
            "strength training", "weightlifting", "bodybuilding", "krafttraining",
            "musculation", "muscu")),
        new CanonicalActivity("triathlon", List.of("triathlon")),
        new CanonicalActivity("squash", List.of("squash")),
        new CanonicalActivity("pilates", List.of("pilates")),
        new CanonicalActivity("stretching", List.of(
            "stretching", "dehnen", "etirements", "etirement")),
        new CanonicalActivity("tai_chi", List.of("tai chi", "taichi", "tai-chi")),
        new CanonicalActivity("dance", List.of(
            "dance", "dancing", "tanzen", "tanz", "danse")),
        new CanonicalActivity("boxing", List.of("boxing", "box", "boxen", "boxe")),
        new CanonicalActivity("climbing", List.of(
            "climbing", "climb", "klettern", "escalade", "grimpe")),
        new CanonicalActivity("taekwondo", List.of("taekwondo")),
        new CanonicalActivity("muay_thai", List.of(
            "muay thai", "thai boxing", "thaiboxen", "boxe thailandaise", "boxe thai")),
        new CanonicalActivity("surfing", List.of("surfing", "surf", "surfen")),
        new CanonicalActivity("paddle", List.of(
            "paddle", "paddleboard", "stand up paddle", "sup", "paddeln")),
        new CanonicalActivity("skiing", List.of(
            "skiing", "ski", "ski alpin", "skifahren", "alpinski")),
        new CanonicalActivity("rowing", List.of("rowing", "row", "rudern", "aviron"))
    );

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");

    /** Normalise : minuscules, sans accents, ponctuation retirée. */
    public String normalize(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        String stripped = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_ALNUM.matcher(stripped).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    /**
     * Résout une ou plusieurs requêtes libres vers les slugs canoniques connus.
     * Chaque texte est comparé indépendamment ; les résultats sont fusionnés.
     */
    public Set<String> matchSlugs(String... texts) {
        Set<String> slugs = new LinkedHashSet<>();
        for (String text : texts) {
            String normalized = normalize(text);
            if (normalized.isEmpty()) continue;
            for (CanonicalActivity entry : ENTRIES) {
                if (matchesEntry(normalized, entry)) {
                    slugs.add(entry.slug());
                }
            }
        }
        return slugs;
    }

    /**
     * Retourne l'ensemble des libellés (toutes langues confondues) des activités
     * canoniques résolues à partir des textes fournis. Utilisé pour interroger
     * la base par ILIKE sur le nom/description des activités stockées.
     */
    public Set<String> matchLabels(String... texts) {
        Set<String> slugs = matchSlugs(texts);
        Set<String> labels = new LinkedHashSet<>();
        for (CanonicalActivity entry : ENTRIES) {
            if (slugs.contains(entry.slug())) {
                labels.addAll(entry.labels());
            }
        }
        return labels;
    }

    /** Liste des slugs canoniques connus, pour injection dans le prompt LLM. */
    public List<String> knownSlugs() {
        return ENTRIES.stream().map(CanonicalActivity::slug).toList();
    }

    private boolean matchesEntry(String normalizedQuery, CanonicalActivity entry) {
        for (String label : entry.labels()) {
            String normalizedLabel = normalize(label);
            if (normalizedLabel.isEmpty()) continue;
            if (containsWord(normalizedQuery, normalizedLabel)) {
                return true;
            }
        }
        return false;
    }

    /** true si le libellé apparaît comme sous-séquence de mots entiers dans la requête. */
    private boolean containsWord(String normalizedQuery, String normalizedLabel) {
        if (normalizedLabel.contains(" ")) {
            return (" " + normalizedQuery + " ").contains(" " + normalizedLabel + " ");
        }
        for (String token : normalizedQuery.split(" ")) {
            if (token.equals(normalizedLabel)) return true;
        }
        return false;
    }
}
