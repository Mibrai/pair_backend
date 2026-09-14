package org.program.pair.domain.publicslot;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** P-MS-17 — {@code assetlinks.json} porte plusieurs empreintes, et jamais une fausse. */
class AppLinksControllerTest {

    private static final String CLE_APP = "AA:" .repeat(31) + "AA";
    private static final String CLE_UPLOAD = "b1:".repeat(31) + "b1";

    private static AppLinksController controleur(String empreintes) {
        AppLinksController c = new AppLinksController();
        ReflectionTestUtils.setField(c, "appleTeamId", "97727T64DH");
        ReflectionTestUtils.setField(c, "bundleId", "com.meetdo.app");
        ReflectionTestUtils.setField(c, "androidSha256", empreintes);
        return c;
    }

    @Test
    void deuxEmpreintes_sontServiesToutesLesDeux() {
        ResponseEntity<String> r = controleur(CLE_APP + ", " + CLE_UPLOAD).assetLinks();

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"" + CLE_APP + "\"")
            .contains("\"" + CLE_UPLOAD.toUpperCase() + "\"")
            .contains("com.meetdo.app");
    }

    // — les motifs de réinitialisation, derrière leur interrupteur (14/09/2026) —

    @Test
    void interrupteurEteint_laReinitialisationNEstPasConfieeALApp() throws Exception {
        String aasa = controleur("").appleAppSiteAssociation().getBody();

        assertThat(aasa).doesNotContain("/r/*").doesNotContain("reset-password").contains("/v/*");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(aasa)
            .at("/applinks/details/0/components").size()).isEqualTo(5);
    }

    @Test
    void interrupteurAllume_lesDeuxMotifsRejoignentLeFichier() throws Exception {
        AppLinksController c = controleur("");
        ReflectionTestUtils.setField(c, "reinitialisationDansApp", true);
        String aasa = c.appleAppSiteAssociation().getBody();

        var composants = new com.fasterxml.jackson.databind.ObjectMapper().readTree(aasa)
            .at("/applinks/details/0/components");
        assertThat(composants.size()).isEqualTo(7);
        assertThat(composants.get(5).get("/").asText()).isEqualTo("/r/*");
        assertThat(composants.get(6).get("/").asText()).isEqualTo("/reset-password");
        assertThat(composants.get(6).get("?").get("token").asText()).isEqualTo("*");
    }

    @Test
    void sansEmpreinte_rienNEstServi() {
        assertThat(controleur("").assetLinks().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void uneValeurProvisoire_empecheToutePublication() {
        assertThat(controleur(CLE_APP + ",EN ATTENTE P-MS-08").assetLinks().getStatusCode().value())
            .as("une empreinte fausse mise en cache par Google est pire qu'aucun fichier")
            .isEqualTo(404);
    }
}
