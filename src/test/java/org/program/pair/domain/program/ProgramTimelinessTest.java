package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La doctrine du « déjà passé », sur la maille programme.
 *
 * <p>Ces cinq tests sont la <b>définition exécutable</b> de ce que
 * {@code isExpired} veut dire — celle que {@code POST /api/search} doit rendre
 * mot pour mot comme {@code GET /activities/browse}. Ils portent sur
 * {@link ProgramTimeliness} parce que c'est là que le verdict vit une seule
 * fois ; les deux chemins qui le rendent (mapping d'entités et requêtes
 * natives) y passent tous les deux.
 *
 * <p>Aucun n'utilise {@code Instant.now()} : le verdict est une comparaison à un
 * instant, et le laisser à l'horloge de la machine ferait de ces tests des
 * mesures plutôt que des définitions.
 */
class ProgramTimelinessTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    /**
     * La règle que le client a écrite en toutes lettres : « un programme qu'on
     * vient de créer et dont l'horaire n'est pas encore posé reste vivant — le
     * griser punirait son auteur pour une étape qu'il n'a pas faite ».
     */
    @Test
    void sansAucunCreneau_leProgrammeNestJamaisExpire() {
        assertThat(ProgramTimeliness.of(List.of(), NOW))
            .isEqualTo(ProgramTimeliness.UNDATED);
        assertThat(ProgramTimeliness.of(null, NOW).isExpired()).isFalse();
        assertThat(ProgramTimeliness.of(null, NOW).nextSessionAt()).isNull();
    }

    @Test
    void unCreneauAVenir_donneSonDebutEtAucuneExpiration() {
        Instant demain = NOW.plus(1, ChronoUnit.DAYS);

        ProgramTimeliness verdict = ProgramTimeliness.of(
            List.of(slot(demain, demain.plus(1, ChronoUnit.HOURS), SlotStatus.OPEN)), NOW);

        assertThat(verdict.nextSessionAt()).isEqualTo(demain);
        assertThat(verdict.isExpired()).isFalse();
    }

    /**
     * <b>Le test le plus important du fichier.</b> « Terminé » se mesure sur la
     * fin, jamais sur le début. Trois surfaces de ce dépôt ont déjà borné le
     * futur sur {@code startsAt}, et chacune faisait disparaître un programme
     * <i>à la seconde où son cours commençait</i> — c'est-à-dire à la minute où
     * il prouve qu'il est vivant.
     */
    @Test
    void uneSeanceEnCours_neRendPasLeProgrammeExpire() {
        Instant debut = NOW.minus(30, ChronoUnit.MINUTES);

        ProgramTimeliness verdict = ProgramTimeliness.of(
            List.of(slot(debut, debut.plus(2, ChronoUnit.HOURS), SlotStatus.OPEN)), NOW);

        assertThat(verdict.nextSessionAt()).isEqualTo(debut);
        assertThat(verdict.isExpired()).isFalse();
    }

    /**
     * Sans fin déclarée, la convention du dépôt s'applique — deux heures, lues
     * sur {@link SlotTiming} et nulle part ailleurs. Le créneau ci-dessous a
     * commencé il y a trois heures : il est fini, et le programme avec lui.
     */
    @Test
    void tousLesCreneauxTermines_donnentExpireEtProchaineSeanceNulle() {
        Instant vieux = NOW.minus(3, ChronoUnit.HOURS);

        ProgramTimeliness verdict = ProgramTimeliness.of(
            List.of(slot(vieux, null, SlotStatus.PAST)), NOW);

        assertThat(verdict.isExpired()).isTrue();
        assertThat(verdict.nextSessionAt())
            .as("expiré implique nextSessionAt nul, sans exception")
            .isNull();
    }

    /**
     * Les deux moitiés de l'asymétrie, dans le même test parce qu'elles ne se
     * comprennent qu'ensemble : un créneau annulé ne compte pas comme séance à
     * venir — sinon un programme sans plus rien de vivant garderait un pin —
     * mais il compte comme <b>date</b>. Le dire non daté le rendrait
     * éternellement vivant, et c'est le {@code COUNT(*)} de
     * {@code UserActivityRepository.browse} qui fixe cette moitié-là.
     */
    @Test
    void unCreneauAnnule_neComptePasCommeSeanceAVenir_maisCompteCommeDate() {
        Instant demain = NOW.plus(1, ChronoUnit.DAYS);

        ProgramTimeliness verdict = ProgramTimeliness.of(
            List.of(slot(demain, demain.plus(1, ChronoUnit.HOURS), SlotStatus.CANCELLED)), NOW);

        assertThat(verdict.nextSessionAt()).isNull();
        assertThat(verdict.isExpired()).isTrue();
    }

    /** Le pont avec le SQL : les deux colonnes de la jointure {@code agenda}. */
    @Test
    void depuisLAgregatSql_leVerdictEstLeMeme() {
        assertThat(ProgramTimeliness.fromAgenda(0, null)).isEqualTo(ProgramTimeliness.UNDATED);
        assertThat(ProgramTimeliness.fromAgenda(3, null).isExpired()).isTrue();
        assertThat(ProgramTimeliness.fromAgenda(3, NOW).isExpired()).isFalse();
        assertThat(ProgramTimeliness.fromAgenda(3, NOW).nextSessionAt()).isEqualTo(NOW);
    }

    private static Schedule slot(Instant startsAt, Instant endsAt, SlotStatus status) {
        return Schedule.builder()
            .startsAt(startsAt)
            .endsAt(endsAt)
            .status(status)
            .build();
    }
}
