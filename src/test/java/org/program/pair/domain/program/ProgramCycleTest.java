package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La frontière du cycle, et surtout le piège qu'elle existe pour rendre
 * inexprimable.
 *
 * <p>Trois surfaces du dépôt bornent le futur sur le <b>début</b> d'une séance
 * et la considèrent donc passée à la seconde où elle commence. Une étape 7
 * écrite sur ce modèle enverrait « ton cycle est bouclé » pendant le cours, à
 * quelqu'un qui y est. C'est la famille de défaut que le client a corrigée trois
 * fois en trois jours ; le premier test de cette classe est celui qui
 * l'attraperait.
 */
class ProgramCycleTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    // ------------------------------------------------------------------
    // Le piège
    // ------------------------------------------------------------------

    @Test
    void unCycleNEstPasClos_pendantQueLaSeanceALieu() {
        // Démarrée il y a 20 minutes, elle finit dans 40 : l'horizon est devant
        // nous. Un prédicat écrit sur startsAt aurait dit « plus rien à venir ».
        Schedule enCours = slot(NOW.minus(Duration.ofMinutes(20)), NOW.plus(Duration.ofMinutes(40)));

        assertThat(ProgramCycle.horizon(List.of(enCours))).isAfter(NOW);
        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(enCours)), NOW)).isFalse();
    }

    @Test
    void unCycleNEstPasClos_pendantUneSeanceSansFinDeclaree() {
        // endsAt nulle — le cas que CreateScheduleRequest autorise. La convention
        // de SlotTiming (deux heures) prend le relais : commencée il y a 20
        // minutes, elle n'est pas finie.
        Schedule enCours = slot(NOW.minus(Duration.ofMinutes(20)), null);

        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(enCours)), NOW)).isFalse();
    }

    @Test
    void unCycleNEstPasClos_juste_apresLaFin() {
        // La grâce de 24 h n'est pas décorative : elle absorbe la latence du
        // rollover, qui ne passe que toutes les dix minutes.
        Schedule finieIlYaUneHeure =
            slot(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));

        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(finieIlYaUneHeure)), NOW))
            .isFalse();
    }

    @Test
    void unCycleEstClos_vingtQuatreHeuresApresLaDerniereFin() {
        Schedule finie = slot(NOW.minus(Duration.ofHours(27)), NOW.minus(Duration.ofHours(25)));

        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(finie)), NOW)).isTrue();
    }

    @Test
    void laGraceSeCompteSurLaFinConventionnelle_quandLaFinNEstPasDeclaree() {
        // Commencée il y a 25 h, sans fin déclarée : elle est réputée finie il y
        // a 23 h. Pas encore 24. Compter la grâce depuis le début aurait relancé
        // une heure trop tôt.
        Schedule sansFin = slot(NOW.minus(Duration.ofHours(25)), null);
        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(sansFin)), NOW)).isFalse();

        Schedule plusAncienne = slot(NOW.minus(Duration.ofHours(27)), null);
        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(plusAncienne)), NOW)).isTrue();
    }

    // ------------------------------------------------------------------
    // L'horizon
    // ------------------------------------------------------------------

    @Test
    void lHorizonEstLaDerniereFin_pasLaPremiere() {
        Schedule ancienne = slot(NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(9)));
        Schedule recente = slot(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(28)));

        assertThat(ProgramCycle.horizon(List.of(ancienne, recente)))
            .isEqualTo(NOW.minus(Duration.ofHours(28)));
    }

    @Test
    void uneSeanceAVenirRepousseLHorizon_memeSiLesAutresSontFinies() {
        // Le cas d'un programme qui a déjà tourné et qui repart : son cycle n'est
        // pas clos, il continue.
        Schedule finie = slot(NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)));
        Schedule aVenir = slot(NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)));

        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of(finie, aVenir)), NOW))
            .isFalse();
    }

    @Test
    void unCreneauAnnuleNeCompteNiDansLHorizonNiDansLePin() {
        Schedule annule = cancelled(NOW.plus(Duration.ofDays(2)));

        // Ni pin sur la carte, ni horizon : ce programme relève de l'étape 2,
        // jamais de la 7. Les deux étapes sont disjointes par construction.
        assertThat(ProgramCycle.hasLiveSlot(List.of(annule))).isFalse();
        assertThat(ProgramCycle.horizon(List.of(annule))).isNull();
        assertThat(ProgramCycle.closedBy(null, NOW)).isFalse();
    }

    @Test
    void unProgrammeSansAucunCreneau_naPasDeCycleARefermer() {
        assertThat(ProgramCycle.horizon(List.of())).isNull();
        assertThat(ProgramCycle.hasLiveSlot(List.of())).isFalse();
        assertThat(ProgramCycle.closedBy(ProgramCycle.horizon(List.of()), NOW)).isFalse();
    }

    // ------------------------------------------------------------------
    // La prochaine séance non terminée — le prédicat de l'étape 4
    // ------------------------------------------------------------------

    @Test
    void laProchaineSeanceNonTerminee_estLaSeanceEnCours_pasLaSuivante() {
        // C'est ce qui empêche « personne ne s'est inscrit » de partir pendant le
        // cours : sauter par-dessus la séance en cours rendrait vrai « la
        // prochaine est dans plus de 48 h ».
        Schedule enCours = slot(NOW.minus(Duration.ofMinutes(20)), NOW.plus(Duration.ofMinutes(40)));
        Schedule dansUneSemaine = slot(NOW.plus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(7)));

        assertThat(ProgramCycle.nextUnfinishedStart(List.of(enCours, dansUneSemaine), NOW))
            .isEqualTo(enCours.getStartsAt())
            .isBefore(NOW);
    }

    @Test
    void laProchaineSeanceNonTerminee_ignoreCeQuiEstFini() {
        Schedule finie = slot(NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)));
        Schedule aVenir = slot(NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(3)));

        assertThat(ProgramCycle.nextUnfinishedStart(List.of(finie, aVenir), NOW))
            .isEqualTo(aVenir.getStartsAt());
    }

    @Test
    void laProchaineSeanceNonTerminee_estNulleQuandToutEstFini() {
        Schedule finie = slot(NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)));

        assertThat(ProgramCycle.nextUnfinishedStart(List.of(finie), NOW)).isNull();
    }

    // ------------------------------------------------------------------
    // La publication — l'origine des 48 h de l'étape 4
    // ------------------------------------------------------------------

    @Test
    void laPublicationEstLaCreationDuPlusAncienCreneauVivant() {
        Schedule premier = slot(NOW.plus(Duration.ofDays(1)), null);
        premier.setCreatedAt(NOW.minus(Duration.ofDays(5)));
        Schedule second = slot(NOW.plus(Duration.ofDays(2)), null);
        second.setCreatedAt(NOW.minus(Duration.ofDays(1)));

        assertThat(ProgramCycle.publishedAt(List.of(second, premier)))
            .isEqualTo(NOW.minus(Duration.ofDays(5)));
    }

    @Test
    void laPublicationIgnoreLesCreneauxAnnules() {
        Schedule annuleAncien = cancelled(NOW.plus(Duration.ofDays(1)));
        annuleAncien.setCreatedAt(NOW.minus(Duration.ofDays(9)));
        Schedule vivant = slot(NOW.plus(Duration.ofDays(2)), null);
        vivant.setCreatedAt(NOW.minus(Duration.ofDays(3)));

        assertThat(ProgramCycle.publishedAt(List.of(annuleAncien, vivant)))
            .isEqualTo(NOW.minus(Duration.ofDays(3)));
    }

    // ------------------------------------------------------------------

    private static Schedule slot(Instant startsAt, Instant endsAt) {
        return Schedule.builder()
            .startsAt(startsAt)
            .endsAt(endsAt)
            .status(SlotStatus.OPEN)
            .build();
    }

    private static Schedule cancelled(Instant startsAt) {
        return Schedule.builder()
            .startsAt(startsAt)
            .status(SlotStatus.CANCELLED)
            .build();
    }
}
