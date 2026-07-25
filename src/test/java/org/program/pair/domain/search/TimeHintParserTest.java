package org.program.pair.domain.search;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimeHintParserTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @Test
    void absent_devraitDonnerLaFenetreParDefautDeSeptJours() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow(null, now);

        assertThat(window.from()).isEqualTo(now);
        assertThat(window.to()).isEqualTo(now.plus(java.time.Duration.ofDays(7)));
    }

    @Test
    void demain_devraitCouvrirLaJourneeEntiereDeDemain() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow("demain", now);

        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);
        assertThat(window.from()).isEqualTo(tomorrow.atStartOfDay(ZONE).toInstant());
        assertThat(window.to()).isAfter(window.from());
        assertThat(ZonedDateTime.ofInstant(window.to(), ZONE).toLocalDate()).isEqualTo(tomorrow);
    }

    @Test
    void demainSoir_devraitCommencerA17h() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow("demain soir", now);

        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);
        assertThat(ZonedDateTime.ofInstant(window.from(), ZONE).toLocalTime().getHour()).isEqualTo(17);
        assertThat(ZonedDateTime.ofInstant(window.from(), ZONE).toLocalDate()).isEqualTo(tomorrow);
    }

    @Test
    void ceWeekEnd_devraitCouvrirSamediEtDimanche() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow("ce week-end", now);

        ZonedDateTime from = ZonedDateTime.ofInstant(window.from(), ZONE);
        ZonedDateTime to = ZonedDateTime.ofInstant(window.to(), ZONE);

        assertThat(to.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(from.getDayOfWeek()).isIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(from).isBeforeOrEqualTo(to);
    }

    @Test
    void ceSoir_devraitCommencerA17hAujourdHui() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow("ce soir", now);

        ZonedDateTime from = ZonedDateTime.ofInstant(window.from(), ZONE);
        assertThat(from.toLocalDate()).isEqualTo(LocalDate.now(ZONE));
        // Ne recule jamais avant "maintenant" si on est déjà après 17h.
        assertThat(window.from()).isAfterOrEqualTo(now.minusSeconds(1));
    }

    @Test
    void expressionInconnue_devraitRetomberSurLaFenetreParDefaut() {
        Instant now = Instant.now();
        var window = TimeHintParser.resolveWindow("un mardi de préférence", now);

        assertThat(window.from()).isEqualTo(now);
        assertThat(window.to()).isEqualTo(now.plus(java.time.Duration.ofDays(7)));
    }

    @Test
    void aucunCas_neDoitJamaisRenvoyerUneFenetreDansLePasse() {
        Instant now = Instant.now();
        for (String hint : new String[]{"ce soir", "demain", "demain soir", "ce week-end", "cette semaine", null}) {
            var window = TimeHintParser.resolveWindow(hint, now);
            assertThat(window.from()).as("hint=" + hint).isAfterOrEqualTo(now.minusSeconds(1));
            assertThat(window.to()).as("hint=" + hint).isAfter(window.from());
        }
    }
}
