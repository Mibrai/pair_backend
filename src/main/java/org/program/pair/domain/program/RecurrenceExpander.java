package org.program.pair.domain.program;

import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.model.Recur;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Développe les règles de récurrence RFC 5545 des créneaux.
 *
 * <p>Le champ {@code recurrence_rule} était stocké, relu dans les DTO, et
 * jamais interprété : un créneau hebdomadaire gardait le {@code startsAt} de sa
 * première séance, si bien que le comparer à « maintenant » déclarait terminée
 * une activité qui a lieu chaque semaine.
 *
 * <p><b>Fuseau.</b> {@code BYDAY=MO} désigne un lundi <i>local</i>, pas un lundi
 * UTC. Un créneau à 00h30 à Paris tombe le dimanche 23h30 en UTC : développer la
 * règle en UTC le décalerait d'un jour. Les occurrences sont donc calculées dans
 * le fuseau configuré par {@code pair.recurrence.zone} (défaut
 * {@code Europe/Paris}).
 *
 * <p><b>Limite connue.</b> Ce fuseau est unique pour toute l'application, alors
 * que les créneaux portent un lieu. Un créneau organisé hors de ce fuseau, à une
 * heure proche de minuit, peut être développé sur le mauvais jour. Le corriger
 * demande de stocker un fuseau par créneau — ce n'est pas le cas aujourd'hui.
 */
@Slf4j
@Component
public class RecurrenceExpander {

    /**
     * Fenêtres d'énumération successives, en jours. Une règle hebdomadaire est
     * résolue à la première ; les suivantes ne servent qu'aux récurrences
     * espacées. Élargir par paliers plutôt qu'énumérer deux ans d'un coup évite
     * de produire 730 dates pour en garder une sur une règle quotidienne.
     */
    private static final int[] HORIZONS_DAYS = {8, 40, 400, 731};

    /** Plafond d'occurrences rendues par {@link #occurrencesBetween}. */
    private static final int MAX_OCCURRENCES = 500;

    private final ZoneId zone;

    public RecurrenceExpander(@Value("${pair.recurrence.zone:Europe/Paris}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    /**
     * Prochaine occurrence strictement postérieure à {@code from}.
     *
     * @param seed  début de la première séance ({@code schedules.starts_at})
     * @param rule  règle RFC 5545 sans le préfixe {@code RRULE:}, ou {@code null}
     * @param from  borne exclusive, en général « maintenant »
     * @return l'occurrence, ou {@code null} s'il n'y en a plus — série terminée
     *         par {@code UNTIL} ou {@code COUNT}, ou séance unique déjà passée
     */
    public Instant nextOccurrence(Instant seed, String rule, Instant from) {
        if (seed == null) {
            return null;
        }
        if (rule == null || rule.isBlank()) {
            // Séance unique : elle est sa propre et seule occurrence.
            return seed.isAfter(from) ? seed : null;
        }

        try {
            ZonedDateTime seedLocal = seed.atZone(zone);
            ZonedDateTime fromLocal = from.atZone(zone);
            Recur<ZonedDateTime> recur = new Recur<>(stripPrefix(rule));

            // Volontairement getDates() et non getNextDate() : sur
            // FREQ=WEEKLY;BYDAY=MO,WE évalué un mardi, getNextDate() d'ical4j
            // 4.0.4 renvoie le LUNDI suivant et saute le mercredi — il avance
            // par périodes entières depuis la graine. C'est précisément le bug
            // que le client nous demande de corriger ; l'énumération, elle,
            // produit bien mercredi puis lundi puis mercredi.
            for (int horizonDays : HORIZONS_DAYS) {
                ZonedDateTime next = recur.getDates(seedLocal, fromLocal, fromLocal.plusDays(horizonDays))
                    .stream()
                    .filter(candidate -> candidate.isAfter(fromLocal))
                    .findFirst()
                    .orElse(null);
                if (next != null) {
                    return next.toInstant();
                }
            }
            // Rien dans les deux ans : série éteinte, ou récurrence si espacée
            // qu'aucun écran ne l'affichera utilement.
            return null;
        } catch (Exception malformed) {
            // Une règle illisible ne doit pas faire disparaître le créneau de
            // l'API : on retombe sur le comportement d'avant, la séance unique.
            log.warn("Règle de récurrence illisible ({}) : {}", rule, malformed.getMessage());
            return seed.isAfter(from) ? seed : null;
        }
    }

    /**
     * Toutes les occurrences dont le début tombe dans {@code [from, to]}, bornes
     * comprises.
     *
     * <p>Complément de {@link #nextOccurrence} : celle-ci répond « quand est la
     * prochaine ? », celle-là « lesquelles tombent dans cette fenêtre ? ». La
     * détection de chevauchement d'agenda a besoin de la seconde question — deux
     * séances hebdomadaires décalées d'un jour ne se heurtent pas à leur première
     * occurrence mais à la cinquième, et ne comparer que les prochaines ferait
     * conclure à tort qu'elles sont compatibles.
     *
     * <p>Le nombre d'occurrences est plafonné à {@value #MAX_OCCURRENCES} : une
     * règle quotidienne sur une fenêtre de trois mois en produit une centaine, une
     * règle horaire mal formée en produirait des milliers. Le plafond protège la
     * réponse HTTP, pas la justesse : sur les fenêtres que nous utilisons, il
     * n'est pas atteint par une récurrence réaliste.
     *
     * @return les occurrences dans l'ordre chronologique, éventuellement vide
     */
    public List<Instant> occurrencesBetween(Instant seed, String rule, Instant from, Instant to) {
        if (seed == null || from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        if (rule == null || rule.isBlank()) {
            // Séance unique : elle est sa propre et seule occurrence.
            return seed.isBefore(from) || seed.isAfter(to) ? List.of() : List.of(seed);
        }

        try {
            Recur<ZonedDateTime> recur = new Recur<>(stripPrefix(rule));
            return recur.getDates(seed.atZone(zone), from.atZone(zone), to.atZone(zone))
                .stream()
                .map(ZonedDateTime::toInstant)
                .filter(occurrence -> !occurrence.isBefore(from) && !occurrence.isAfter(to))
                .sorted()
                .limit(MAX_OCCURRENCES)
                .toList();
        } catch (Exception malformed) {
            // Même repli que nextOccurrence : une règle illisible dégrade le
            // créneau en séance unique plutôt que de le faire disparaître.
            log.warn("Règle de récurrence illisible ({}) : {}", rule, malformed.getMessage());
            return seed.isBefore(from) || seed.isAfter(to) ? List.of() : List.of(seed);
        }
    }

    /**
     * Vrai si la série est terminée : elle avait au moins une séance, et n'en a
     * plus aucune à venir. Une entrée sans créneau du tout n'est jamais expirée —
     * c'est à l'appelant de ne pas nous la soumettre.
     */
    public boolean isExpired(Instant seed, String rule, Instant from) {
        return seed != null && nextOccurrence(seed, rule, from) == null;
    }

    private String stripPrefix(String rule) {
        String trimmed = rule.trim();
        return trimmed.regionMatches(true, 0, "RRULE:", 0, 6) ? trimmed.substring(6) : trimmed;
    }
}
