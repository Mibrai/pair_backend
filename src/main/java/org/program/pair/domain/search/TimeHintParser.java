package org.program.pair.domain.search;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Traduit le `timeHint` en texte libre extrait par le LLM (ex. "demain soir",
 * "ce week-end") en une fenêtre [from, to] bornant la recherche de créneaux.
 * Reconnaissance par mots-clés, best-effort : une expression non reconnue
 * retombe sur la fenêtre par défaut (maintenant -> +7 jours), la même que
 * /api/slots/feed sans paramètre de date — jamais d'erreur, jamais de fenêtre
 * vide par excès de zèle sur une formulation inattendue.
 */
public final class TimeHintParser {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private TimeHintParser() {}

    public record Window(Instant from, Instant to) {}

    public static Window resolveWindow(String timeHint, Instant now) {
        LocalDate today = LocalDate.now(ZONE);
        String hint = timeHint == null ? "" : timeHint.toLowerCase(Locale.ROOT);

        if (hint.isBlank()) {
            return new Window(now, now.plus(java.time.Duration.ofDays(7)));
        }

        boolean mentionsWeekend = hint.contains("week-end") || hint.contains("weekend")
            || hint.contains("wochenende");
        boolean mentionsTomorrow = hint.contains("demain") || hint.contains("morgen");
        boolean mentionsEvening = hint.contains("soir") || hint.contains("abend");
        boolean mentionsThisWeek = hint.contains("cette semaine") || hint.contains("diese woche");
        boolean mentionsTonight = hint.contains("ce soir") || hint.contains("aujourd'hui")
            || hint.contains("heute");

        if (mentionsWeekend) {
            LocalDate saturday = today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? today.minusDays(1)
                : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            LocalDate sunday = saturday.plusDays(1);
            Instant from = maxOf(now, atStartOfDay(saturday));
            Instant to = atEndOfDay(sunday);
            return new Window(from, to);
        }

        if (mentionsTomorrow) {
            LocalDate tomorrow = today.plusDays(1);
            if (mentionsEvening) {
                return new Window(atTime(tomorrow, 17, 0), atEndOfDay(tomorrow));
            }
            return new Window(atStartOfDay(tomorrow), atEndOfDay(tomorrow));
        }

        if (mentionsTonight && mentionsEvening) {
            return new Window(maxOf(now, atTime(today, 17, 0)), atEndOfDay(today));
        }

        if (mentionsThisWeek) {
            LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            return new Window(now, atEndOfDay(sunday));
        }

        return new Window(now, now.plus(java.time.Duration.ofDays(7)));
    }

    private static Instant maxOf(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant atStartOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    private static Instant atEndOfDay(LocalDate date) {
        return date.atTime(LocalTime.of(23, 59, 59)).atZone(ZONE).toInstant();
    }

    private static Instant atTime(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ZONE).toInstant();
    }
}
