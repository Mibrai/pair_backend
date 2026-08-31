package org.program.pair.domain.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le vocabulaire servi au signalant est fermé sur trois mots.
 *
 * <p>Test unitaire et non d'intégration, délibérément : la question porte sur
 * l'exhaustivité de la projection, pas sur son transport. La vérifier par HTTP
 * demanderait un compte et un signalement par statut, soit plus d'inscriptions
 * que le limiteur n'en accorde à une méthode de test — et échouerait alors pour
 * une raison sans rapport avec ce qu'elle vérifie. Le transport, lui, est
 * couvert par {@code MesSignalementsFormeIntegrationTest}.
 */
class ReportPublicStateTest {

    /**
     * <p>Ce test échouera le jour où quelqu'un ajoutera une valeur à
     * {@link ReportStatus} — le {@code switch} de {@link ReportPublicState#of}
     * étant exhaustif, la compilation échouera même avant. C'est voulu : chaque
     * état de modération doit recevoir une projection décidée, jamais une
     * projection par défaut.
     */
    @Test
    void toutStatutDeModeration_doitSeProjeterSurLesTroisMotsConvenus() {
        for (ReportStatus status : ReportStatus.values()) {
            assertThat(ReportPublicState.of(status))
                .as("projection de %s", status)
                .isNotNull()
                .isIn(ReportPublicState.RECEIVED,
                      ReportPublicState.RESOLVED,
                      ReportPublicState.DISMISSED);
        }
    }

    /**
     * {@code REVIEWED} et {@code ACTIONED} deviennent le même mot.
     *
     * <p>La distinction dit si une sanction a suivi, et cela regarde l'équipe
     * qui traite, pas la personne qui a signalé : le lui dire reviendrait à lui
     * rendre compte de ce qui est arrivé à quelqu'un d'autre.
     */
    @Test
    void traiteEtSanctionne_doiventDonnerLeMemeMot() {
        assertThat(ReportPublicState.of(ReportStatus.REVIEWED))
            .isEqualTo(ReportPublicState.of(ReportStatus.ACTIONED))
            .isEqualTo(ReportPublicState.RESOLVED);
    }

    /**
     * {@code IN_REVIEW} n'est pas servi, alors que le contrat client le prévoit.
     *
     * <p>Aucun geste de modération ne l'écrirait : un modérateur passe de
     * {@code PENDING} à son verdict en une fois. Le servir ferait un écran qui
     * n'affiche jamais « en cours », ce qui ment autant qu'un écran qui
     * l'afficherait toujours. Le client retombe sur {@code RECEIVED} pour toute
     * valeur inconnue : le jour où la modération gagne ce geste, la valeur peut
     * être servie sans rien casser et sans prévenir personne.
     */
    @Test
    void enCoursDeTraitement_neDoitPasExisterTantQueRienNeLecrit() {
        assertThat(ReportPublicState.values())
            .extracting(Enum::name)
            .doesNotContain("IN_REVIEW");
    }

    /** Personne en attente ne doit lire autre chose que « reçu ». */
    @Test
    void enAttente_doitSeLireRecu() {
        assertThat(ReportPublicState.of(ReportStatus.PENDING))
            .isEqualTo(ReportPublicState.RECEIVED);
    }

    /** Classé sans suite se dit, il ne se déguise pas en « en cours ». */
    @Test
    void classeSansSuite_doitResterClasseSansSuite() {
        assertThat(ReportPublicState.of(ReportStatus.DISMISSED))
            .isEqualTo(ReportPublicState.DISMISSED);
    }
}
