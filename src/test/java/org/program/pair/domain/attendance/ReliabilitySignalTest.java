package org.program.pair.domain.attendance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le signal de fiabilité, et surtout ce qu'il refuse de dire.
 *
 * <p>La plupart de ces cas vérifient une absence. C'est le sujet : la règle
 * produit tient moins à ce que le libellé affirme qu'à ce qu'il ne peut pas
 * devenir.
 */
class ReliabilitySignalTest {

    @Test
    void sousLeSeuil_onNeDitRien() {
        // Deux venues sur deux ne prouvent rien, et une absence sur deux non
        // plus. Le seuil existe pour éviter d'affirmer quelque chose de faux.
        assertThat(ReliabilitySignal.of(2, 2)).isNull();
        assertThat(ReliabilitySignal.of(4, 4)).isNull();
    }

    @Test
    void auDessusDuSeuil_etAssidu_onLeDit() {
        assertThat(ReliabilitySignal.of(5, 5)).isEqualTo("USUALLY_SHOWS_UP");
        assertThat(ReliabilitySignal.of(5, 4)).isEqualTo("USUALLY_SHOWS_UP");
        assertThat(ReliabilitySignal.of(20, 16)).isEqualTo("USUALLY_SHOWS_UP");
    }

    @Test
    void sousLaBarre_onNeDitToujoursRien_jamaisLeContraire() {
        // Le point le plus important du lot : il n'existe aucun libellé négatif.
        // L'absence de signal n'est pas un mauvais signal — c'est l'état de
        // quelqu'un qui vient d'arriver autant que de quelqu'un qui manque.
        assertThat(ReliabilitySignal.of(10, 3)).isNull();
        assertThat(ReliabilitySignal.of(10, 0)).isNull();
    }

    @Test
    void unCompteNeuf_neDoitPasExploser() {
        assertThat(ReliabilitySignal.of(null, null)).isNull();
        assertThat(ReliabilitySignal.of(0, 0)).isNull();
    }

    @Test
    void leSignal_neRendJamaisAutreChoseQuUneValeurOuRien() {
        // Aucun chiffre ne sort d'ici. Si un jour cette assertion doit changer,
        // c'est que la décision produit a changé, pas le calcul.
        for (int joined = 0; joined <= 40; joined++) {
            for (int attended = 0; attended <= joined; attended++) {
                String signal = ReliabilitySignal.of(joined, attended);
                assertThat(signal).isIn(null, "USUALLY_SHOWS_UP");
            }
        }
    }
}
