package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les critères d'acceptation de la demande 4
 * (docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md), au niveau du moteur.
 *
 * <p>Le cas central est {@code FREQ=WEEKLY;BYDAY=MO,WE} évalué un mardi : c'est
 * celui que le moteur du client ne sait pas traiter, et que l'ancien job de
 * rollover — qui avançait de 7 jours en dur — ne pouvait pas traiter non plus.
 */
class RecurrenceExpanderTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private final RecurrenceExpander expander = new RecurrenceExpander("Europe/Paris");

    @Test
    void hebdomadaireDejaPasse_doitRemonterUneOccurrenceFuture() {
        Instant seed = at(2026, 1, 5, 18, 30);              // un lundi
        Instant now = at(2026, 8, 4, 12, 0);                // sept mois plus tard

        Instant next = expander.nextOccurrence(seed, "FREQ=WEEKLY;BYDAY=MO", now);

        assertThat(next).isNotNull();
        assertThat(next).isAfter(now);
        assertThat(dayOf(next)).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void bydayMultiJours_evalueUnMardi_doitRemonterLeMercredi() {
        // LE test de non-régression : le moteur client ne développe pas BYDAY
        // au-delà du jour de la première séance, donc ne propose jamais de
        // mercredi. Le job de rollover, qui avançait de 7 jours, non plus.
        Instant seed = at(2026, 1, 5, 18, 30);              // lundi
        Instant tuesday = at(2026, 8, 4, 12, 0);            // 4 août 2026 = mardi

        assertThat(dayOf(tuesday)).isEqualTo(DayOfWeek.TUESDAY);

        Instant next = expander.nextOccurrence(seed, "FREQ=WEEKLY;BYDAY=MO,WE", tuesday);

        assertThat(dayOf(next))
            .as("le mercredi suivant, pas le lundi suivant")
            .isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(next).isBefore(tuesday.plusSeconds(2 * 86_400));
    }

    @Test
    void untilDepasse_doitRemonterNull_etEtreExpire() {
        Instant seed = at(2026, 1, 5, 18, 30);
        Instant now = at(2026, 8, 4, 12, 0);
        String rule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20260301T000000Z";

        assertThat(expander.nextOccurrence(seed, rule, now)).isNull();
        assertThat(expander.isExpired(seed, rule, now)).isTrue();
    }

    @Test
    void countEpuise_doitRemonterNull() {
        Instant seed = at(2026, 1, 5, 18, 30);
        Instant now = at(2026, 8, 4, 12, 0);

        assertThat(expander.nextOccurrence(seed, "FREQ=WEEKLY;BYDAY=MO;COUNT=4", now)).isNull();
    }

    @Test
    void intervalle_doitEtreRespecte() {
        Instant seed = at(2026, 8, 3, 18, 30);              // lundi
        Instant justAfter = at(2026, 8, 3, 19, 0);

        Instant next = expander.nextOccurrence(seed, "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", justAfter);

        assertThat(next)
            .as("toutes les deux semaines : le 17, pas le 10")
            .isEqualTo(at(2026, 8, 17, 18, 30));
    }

    @Test
    void seanceUniquePassee_doitRemonterNull() {
        Instant seed = at(2026, 1, 5, 18, 30);
        assertThat(expander.nextOccurrence(seed, null, at(2026, 8, 4, 12, 0))).isNull();
    }

    @Test
    void seanceUniqueFuture_estSaPropreOccurrence() {
        Instant seed = at(2026, 12, 5, 18, 30);
        assertThat(expander.nextOccurrence(seed, null, at(2026, 8, 4, 12, 0))).isEqualTo(seed);
    }

    @Test
    void mensuel_doitAvancerDunMois_pasDeSeptJours() {
        // L'ancien job avançait de 7 jours quelle que soit la règle : une série
        // mensuelle en ressortait déplacée, pas reportée.
        Instant seed = at(2026, 1, 15, 18, 30);
        Instant now = at(2026, 8, 4, 12, 0);

        Instant next = expander.nextOccurrence(seed, "FREQ=MONTHLY", now);

        assertThat(next).isEqualTo(at(2026, 8, 15, 18, 30));
    }

    @Test
    void regleIllisible_neDoitPasFaireDisparaitreLeCreneau() {
        Instant futureSeed = at(2026, 12, 5, 18, 30);
        Instant now = at(2026, 8, 4, 12, 0);

        // Repli sur le comportement d'avant : la séance unique.
        assertThat(expander.nextOccurrence(futureSeed, "CECI N'EST PAS UNE RRULE", now))
            .isEqualTo(futureSeed);
    }

    @Test
    void lePrefixeRRULE_doitEtreTolere() {
        Instant seed = at(2026, 1, 5, 18, 30);
        Instant now = at(2026, 8, 4, 12, 0);

        assertThat(expander.nextOccurrence(seed, "RRULE:FREQ=WEEKLY;BYDAY=MO", now))
            .isEqualTo(expander.nextOccurrence(seed, "FREQ=WEEKLY;BYDAY=MO", now));
    }

    @Test
    void unLundiLocalPresDeMinuit_neDoitPasBasculerAuDimanche() {
        // Le motif du choix de fuseau : 00h30 à Paris, c'est dimanche 23h30 UTC.
        // Développer en UTC donnerait des dimanches.
        Instant seed = at(2026, 1, 5, 0, 30);
        Instant now = at(2026, 8, 4, 12, 0);

        Instant next = expander.nextOccurrence(seed, "FREQ=WEEKLY;BYDAY=MO", now);

        assertThat(next.atZone(ZONE).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    private static DayOfWeek dayOf(Instant instant) {
        return instant.atZone(ZONE).getDayOfWeek();
    }
}
