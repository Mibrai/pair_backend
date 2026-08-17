package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De quelle séance parle-t-on ?
 *
 * <p>Sur un créneau récurrent, la ligne {@code schedules} ne décrit jamais un
 * moment passé : {@code RecurringSlotRolloverJob} l'a déjà avancée à
 * l'occurrence suivante. Tout le code de lecture des cartes-souvenirs
 * confondait pourtant les deux, ce qui datait un souvenir de la semaine à
 * venir. Ces tests fixent la distinction avant qu'elle ne se reperde.
 */
class SlotOccurrenceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void unCreneauNonRecurrentTermine_estSaPropreOccurrence() {
        Schedule slot = slot(NOW.minus(3, ChronoUnit.HOURS), NOW.minus(2, ChronoUnit.HOURS));

        SlotOccurrence ended = SlotTiming.lastEndedOccurrence(slot, NOW);

        assertThat(ended).isNotNull();
        assertThat(ended.startsAt()).isEqualTo(slot.getStartsAt());
        assertThat(ended.endsAt()).isEqualTo(slot.getEndsAt());
    }

    @Test
    void unCreneauQuiNaPasEncoreEuLieu_naAucuneSeanceTerminee() {
        Schedule slot = slot(NOW.plus(2, ChronoUnit.HOURS), NOW.plus(3, ChronoUnit.HOURS));

        assertThat(SlotTiming.lastEndedOccurrence(slot, NOW)).isNull();
        assertThat(SlotTiming.hasEndedBy(slot, NOW)).isFalse();
    }

    @Test
    void uneSeanceEnCours_nEstPasEncoreTerminee() {
        // Le cas que le rollover traitait à tort comme passé : commencé n'est
        // pas terminé, et il avançait le créneau pendant qu'on le vivait.
        Schedule slot = slot(NOW.minus(30, ChronoUnit.MINUTES), NOW.plus(30, ChronoUnit.MINUTES));

        assertThat(SlotTiming.lastEndedOccurrence(slot, NOW)).isNull();
    }

    @Test
    void unCreneauSansFinDeclaree_dureDeuxHeuresParConvention() {
        Schedule slot = slot(NOW.minus(90, ChronoUnit.MINUTES), null);

        assertThat(SlotTiming.lastEndedOccurrence(slot, NOW))
            .as("90 minutes après le début, la séance conventionnelle de deux heures court encore")
            .isNull();
        assertThat(SlotTiming.currentOccurrence(slot).endsAt())
            .isEqualTo(slot.getStartsAt().plus(2, ChronoUnit.HOURS));
    }

    @Test
    void unCreneauRecurrentDejaAvance_designeLaSeanceRetiree_pasCelleAVenir() {
        // Exactement l'état que laisse le rollover : la ligne annonce la
        // semaine prochaine, la séance vécue n'existe plus que dans
        // lastOccurrence*.
        Instant vecuDebut = NOW.minus(3, ChronoUnit.HOURS);
        Instant vecuFin   = NOW.minus(2, ChronoUnit.HOURS);

        Schedule slot = slot(vecuDebut.plus(7, ChronoUnit.DAYS), vecuFin.plus(7, ChronoUnit.DAYS));
        slot.setRecurrenceRule("FREQ=WEEKLY");
        slot.setLastOccurrenceStart(vecuDebut);
        slot.setLastOccurrenceEnd(vecuFin);

        SlotOccurrence ended = SlotTiming.lastEndedOccurrence(slot, NOW);

        assertThat(ended).isNotNull();
        assertThat(ended.startsAt())
            .as("la séance dont on parle est celle qu'on vient de vivre")
            .isEqualTo(vecuDebut);
        assertThat(ended.startsAt())
            .as("et surtout pas celle que porte la ligne")
            .isNotEqualTo(slot.getStartsAt());
    }

    @Test
    void unCreneauRecurrentJamaisAvance_seLitCommeUnCreneauOrdinaire() {
        Schedule slot = slot(NOW.minus(3, ChronoUnit.HOURS), NOW.minus(2, ChronoUnit.HOURS));
        slot.setRecurrenceRule("FREQ=WEEKLY");

        assertThat(SlotTiming.lastEndedOccurrence(slot, NOW).startsAt())
            .isEqualTo(slot.getStartsAt());
    }

    private static Schedule slot(Instant startsAt, Instant endsAt) {
        Schedule slot = new Schedule();
        slot.setStartsAt(startsAt);
        slot.setEndsAt(endsAt);
        return slot;
    }
}
