package org.program.pair.domain.search;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.search.dto.SearchIntent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extraction d'intention par règles et mots-clés FR/EN/DE — remplace l'appel
 * LLM (Anthropic) par un pipeline 100% local et gratuit. Ne comprend pas le
 * langage naturel libre : couvre bien les formulations directes, moins bien
 * les tournures très détournées ou un mélange de langues dans une même
 * phrase (mais ne lève jamais d'exception dans ce cas).
 * <p>
 * La résolution d'activité (`canonicalActivitySlug`) est déléguée à
 * {@link ActivityTaxonomy}, déjà trilingue — indispensable pour que
 * {@code SemanticSearchService.resolveActivityId()} continue de fonctionner.
 */
@Service
@RequiredArgsConstructor
public class RuleBasedIntentExtractor {

    private final ActivityTaxonomy activityTaxonomy;

    // Listes ordonnées (et non Map.ofEntries, dont l'ordre d'itération n'est pas
    // garanti) : les expressions les plus spécifiques doivent être testées avant
    // les mots isolés qu'elles contiennent (ex. "seul a seul" avant "seul").
    private static final List<Map.Entry<String, ActivityLevel>> LEVEL_KEYWORDS = List.of(
        // Français
        Map.entry("je debute", ActivityLevel.BEGINNER),
        Map.entry("debutante", ActivityLevel.BEGINNER),
        Map.entry("debutant", ActivityLevel.BEGINNER),
        Map.entry("initiation", ActivityLevel.BEGINNER),
        Map.entry("intermediaire", ActivityLevel.INTERMEDIATE),
        Map.entry("confirmee", ActivityLevel.ADVANCED),
        Map.entry("confirme", ActivityLevel.ADVANCED),
        Map.entry("niveau club", ActivityLevel.ADVANCED),
        Map.entry("avance", ActivityLevel.ADVANCED),
        Map.entry("expert", ActivityLevel.EXPERT),
        // English
        Map.entry("just starting", ActivityLevel.BEGINNER),
        Map.entry("new to this", ActivityLevel.BEGINNER),
        Map.entry("beginner", ActivityLevel.BEGINNER),
        Map.entry("intermediate", ActivityLevel.INTERMEDIATE),
        Map.entry("expert level", ActivityLevel.EXPERT),
        Map.entry("experienced", ActivityLevel.ADVANCED),
        Map.entry("advanced", ActivityLevel.ADVANCED),
        // Deutsch
        Map.entry("anfangerin", ActivityLevel.BEGINNER),
        Map.entry("anfanger", ActivityLevel.BEGINNER),
        Map.entry("einsteiger", ActivityLevel.BEGINNER),
        Map.entry("fortgeschritten", ActivityLevel.INTERMEDIATE),
        Map.entry("erfahren", ActivityLevel.ADVANCED),
        Map.entry("profi", ActivityLevel.ADVANCED),
        Map.entry("expertin", ActivityLevel.EXPERT),
        Map.entry("experte", ActivityLevel.EXPERT)
    );

    private static final List<Map.Entry<String, ActivityFormat>> FORMAT_KEYWORDS = List.of(
        // Français
        Map.entry("seul a seul", ActivityFormat.DUO),
        Map.entry("en duo", ActivityFormat.DUO),
        Map.entry("juste a deux", ActivityFormat.DUO),
        Map.entry("un partenaire", ActivityFormat.DUO),
        Map.entry("une partenaire", ActivityFormat.DUO),
        Map.entry("en groupe", ActivityFormat.GROUP),
        Map.entry("plusieurs personnes", ActivityFormat.GROUP),
        Map.entry("groupe", ActivityFormat.GROUP),
        Map.entry("solo", ActivityFormat.SOLO),
        Map.entry("seul", ActivityFormat.SOLO),
        // English
        Map.entry("one on one", ActivityFormat.DUO),
        Map.entry("just the two of us", ActivityFormat.DUO),
        Map.entry("a partner", ActivityFormat.DUO),
        Map.entry("in a group", ActivityFormat.GROUP),
        Map.entry("several people", ActivityFormat.GROUP),
        Map.entry("group", ActivityFormat.GROUP),
        Map.entry("by myself", ActivityFormat.SOLO),
        // Deutsch
        Map.entry("zu zweit", ActivityFormat.DUO),
        Map.entry("ein partner", ActivityFormat.DUO),
        Map.entry("eine partnerin", ActivityFormat.DUO),
        Map.entry("in der gruppe", ActivityFormat.GROUP),
        Map.entry("mehrere personen", ActivityFormat.GROUP),
        Map.entry("gruppe", ActivityFormat.GROUP),
        Map.entry("allein", ActivityFormat.SOLO)
    );

    /** Jour explicite ("demain", "lundi"...) — au plus un jeton retenu par requête. */
    private static final List<Map.Entry<String, String>> DAY_HINTS = List.of(
        Map.entry("aujourd hui", "aujourd'hui"), Map.entry("today", "aujourd'hui"), Map.entry("heute", "aujourd'hui"),
        Map.entry("demain", "demain"), Map.entry("tomorrow", "demain"), Map.entry("morgen", "demain"),
        Map.entry("lundi", "lundi"), Map.entry("monday", "lundi"), Map.entry("montag", "lundi"),
        Map.entry("mardi", "mardi"), Map.entry("tuesday", "mardi"), Map.entry("dienstag", "mardi"),
        Map.entry("mercredi", "mercredi"), Map.entry("wednesday", "mercredi"), Map.entry("mittwoch", "mercredi"),
        Map.entry("jeudi", "jeudi"), Map.entry("thursday", "jeudi"), Map.entry("donnerstag", "jeudi"),
        Map.entry("vendredi", "vendredi"), Map.entry("friday", "vendredi"), Map.entry("freitag", "vendredi"),
        Map.entry("samedi", "samedi"), Map.entry("saturday", "samedi"), Map.entry("samstag", "samedi"),
        Map.entry("dimanche", "dimanche"), Map.entry("sunday", "dimanche"), Map.entry("sonntag", "dimanche")
    );

    /**
     * Moment de la journée ("matin", "soir") — les expressions allemandes
     * composées (heute morgen/morgens/fruh morgens) doivent être testées
     * avant toute règle "jour" générique sur "morgen" seul (qui signifie
     * "demain"), sans quoi "heute morgen" ("ce matin") serait mal résolu en
     * "demain" (ambiguïté connue, voir findDayHint).
     */
    private static final List<Map.Entry<String, String>> TIME_OF_DAY_HINTS = List.of(
        Map.entry("heute morgen", "matin"), Map.entry("fruh morgens", "matin"), Map.entry("morgens", "matin"),
        Map.entry("ce matin", "matin"), Map.entry("le matin", "matin"), Map.entry("tot le matin", "matin"),
        Map.entry("this morning", "matin"), Map.entry("in the morning", "matin"), Map.entry("early morning", "matin"),
        Map.entry("heute abend", "soir"), Map.entry("abends", "soir"), Map.entry("abend", "soir"),
        Map.entry("ce soir", "soir"), Map.entry("le soir", "soir"), Map.entry("en soiree", "soir"),
        Map.entry("this evening", "soir"), Map.entry("in the evening", "soir"), Map.entry("tonight", "soir"),
        // Repli générique (mot isolé) : couvre les combinaisons non prévues ci-dessus,
        // ex. "demain soir", "tomorrow evening", "tomorrow morning".
        Map.entry("soir", "soir"), Map.entry("matin", "matin"),
        Map.entry("evening", "soir"), Map.entry("morning", "matin")
    );

    /** Plage plus large ("week-end", "cette semaine"). */
    private static final List<Map.Entry<String, String>> RANGE_HINTS = List.of(
        Map.entry("ce week end", "week-end"), Map.entry("week end", "week-end"), Map.entry("weekend", "week-end"),
        Map.entry("this weekend", "week-end"),
        Map.entry("am wochenende", "week-end"), Map.entry("dieses wochenende", "week-end"), Map.entry("wochenende", "week-end"),
        Map.entry("cette semaine", "cette semaine"), Map.entry("this week", "cette semaine"), Map.entry("diese woche", "cette semaine")
    );

    private static final Pattern RADIUS_KM = Pattern.compile(
        "(\\d+)\\s*(?:km|kilometres?|kilometers?|kilometer)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RADIUS_MILES = Pattern.compile(
        "(\\d+)\\s*(?:mi|miles?)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> VAGUE_PHRASES = Set.of(
        "je veux faire du sport", "je cherche une activite", "je m ennuie",
        "je veux sortir", "faire quelque chose", "je veux bouger",
        "trouve moi quelque chose",
        "i want to do something", "i m bored", "looking for an activity",
        "i want to go out", "i want to move", "find me something",
        "ich will etwas tun", "mir ist langweilig", "ich suche eine aktivitat",
        "ich will raus", "ich will mich bewegen", "finde mir etwas"
    );

    public SearchIntent extractIntent(String rawQuery) {
        String normalized = activityTaxonomy.normalize(rawQuery);

        ActivityLevel level = findFirstMatch(normalized, LEVEL_KEYWORDS);
        ActivityFormat format = findFirstMatch(normalized, FORMAT_KEYWORDS);
        String timeHint = buildTimeHint(normalized);
        Integer radiusMeters = extractRadiusMeters(normalized);
        String canonicalSlug = activityTaxonomy.matchSlugs(rawQuery).stream().findFirst().orElse(null);
        boolean needsClarification = isTooVague(normalized, canonicalSlug);

        return new SearchIntent(
            rawQuery,
            null,
            level != null ? level.name() : null,
            format != null ? format.name() : null,
            radiusMeters != null ? radiusMeters : 5000,
            timeHint,
            needsClarification,
            needsClarification ? clarificationQuestionFor(normalized) : null,
            canonicalSlug
        );
    }

    private <T> T findFirstMatch(String normalizedText, List<Map.Entry<String, T>> keywords) {
        return keywords.stream()
            .filter(e -> normalizedText.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    /** Combine au plus un jeton par dimension (jour / moment / plage) en une seule chaîne. */
    private String buildTimeHint(String normalizedText) {
        String timeOfDay = findFirstToken(normalizedText, TIME_OF_DAY_HINTS);
        String day = findDayHint(normalizedText, timeOfDay);
        String range = findFirstToken(normalizedText, RANGE_HINTS);

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (range != null) tokens.add(range);
        if (day != null) tokens.add(day);
        if (timeOfDay != null) tokens.add(timeOfDay);

        return tokens.isEmpty() ? null : String.join(" ", tokens);
    }

    /**
     * "morgen" seul (allemand) signifie "demain", mais fait aussi partie des
     * expressions composées "heute morgen"/"morgens"/"fruh morgens" qui
     * signifient "matin" — déjà capturées par {@code timeOfDay}. Si l'une de
     * ces expressions a matché "matin", on ignore la règle générique
     * "morgen" -> "demain" pour ne pas produire les deux jetons à la fois.
     */
    private String findDayHint(String normalizedText, String timeOfDay) {
        boolean germanMorningPhrase = "matin".equals(timeOfDay)
            && (normalizedText.contains("heute morgen")
                || normalizedText.contains("morgens")
                || normalizedText.contains("fruh morgens"));

        for (Map.Entry<String, String> entry : DAY_HINTS) {
            if (!normalizedText.contains(entry.getKey())) continue;
            if (germanMorningPhrase && entry.getKey().equals("morgen")) continue;
            return entry.getValue();
        }
        return null;
    }

    private String findFirstToken(String normalizedText, List<Map.Entry<String, String>> hints) {
        return hints.stream()
            .filter(e -> normalizedText.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private Integer extractRadiusMeters(String normalizedText) {
        Matcher km = RADIUS_KM.matcher(normalizedText);
        if (km.find()) return Integer.parseInt(km.group(1)) * 1000;
        Matcher mi = RADIUS_MILES.matcher(normalizedText);
        if (mi.find()) return Math.round(Integer.parseInt(mi.group(1)) * 1609f);
        return null;
    }

    private boolean isTooVague(String normalizedText, String canonicalSlug) {
        if (canonicalSlug != null) return false;
        boolean matchesVague = VAGUE_PHRASES.stream().anyMatch(normalizedText::contains);
        boolean isShort = normalizedText.split("\\s+").length <= 6;
        return matchesVague && isShort;
    }

    private String clarificationQuestionFor(String normalizedText) {
        if (normalizedText.matches(".*\\b(i|want|looking|bored)\\b.*")) {
            return "What kind of activity would you enjoy today?";
        }
        if (normalizedText.matches(".*\\b(ich|will|suche|langweilig)\\b.*")) {
            return "Welche Aktivität würde dir heute gefallen?";
        }
        return "Quel type d'activité te ferait plaisir aujourd'hui ?";
    }
}
