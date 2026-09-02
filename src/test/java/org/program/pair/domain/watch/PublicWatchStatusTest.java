package org.program.pair.domain.watch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'un proche lit sur la page publique — et surtout ce qu'il ne doit jamais y
 * lire.
 *
 * <p>Deux affirmations sont interdites sur une non-arrivée, et elles le sont pour
 * des raisons opposées. <b>« Alerte envoyée »</b> serait faux depuis la décision du
 * 02/09 : aucun message ne part plus au contact. <b>« Bien rentrée »</b> serait
 * pire — c'est le résultat qu'aurait donné {@code NOT_ARRIVED} tombant dans la
 * branche des états terminaux, et personne ne serait allé vérifier une page qui
 * annonce une bonne nouvelle.
 */
class PublicWatchStatusTest {

    private static final Instant MAINTENANT = Instant.parse("2026-09-02T21:00:00Z");

    @Test
    void nonArrivee_ditEnTrajet_jamaisRentree() {
        assertThat(statut(WatchState.NOT_ARRIVED, null))
            .isEqualTo(PublicWatchStatus.EN_TRAJET);
    }

    @Test
    void nonArrivee_neDitJamaisAlerteEnvoyee() {
        assertThat(statut(WatchState.NOT_ARRIVED, null))
            .isNotEqualTo(PublicWatchStatus.ALERTE_ENVOYEE);
    }

    /**
     * Le lien hérité : une veille armée avant la décision a pu passer
     * {@code ESCALATED} sans arrivée validée, avec un jeton déjà distribué. Elle
     * n'affiche pas l'alerte non plus.
     */
    @Test
    void escaladeHeriteeSansArrivee_ditEnTrajet() {
        assertThat(statut(WatchState.ESCALATED, null))
            .isEqualTo(PublicWatchStatus.EN_TRAJET);
    }

    /** Une vraie alerte retour — arrivée validée — continue de le dire. */
    @Test
    void escaladeApresArrivee_ditToujoursAlerteEnvoyee() {
        assertThat(statut(WatchState.ESCALATED, MAINTENANT.minus(Duration.ofHours(3))))
            .isEqualTo(PublicWatchStatus.ALERTE_ENVOYEE);
    }

    /** Les états terminaux ordinaires n'ont pas bougé. */
    @Test
    void closeOuResolue_ditToujoursRentree() {
        assertThat(statut(WatchState.CLOSED, MAINTENANT.minus(Duration.ofHours(3))))
            .isEqualTo(PublicWatchStatus.RENTREE);
        assertThat(statut(WatchState.RESOLVED, MAINTENANT.minus(Duration.ofHours(3))))
            .isEqualTo(PublicWatchStatus.RENTREE);
    }

    private static PublicWatchStatus statut(WatchState state, Instant arrivee) {
        Watch watch = Watch.builder()
            .state(state)
            .arrivalConfirmedAt(arrivee)
            .deadlineAt(MAINTENANT.plus(Duration.ofHours(1)))
            .build();
        return PublicWatchStatus.of(watch, MAINTENANT);
    }
}
