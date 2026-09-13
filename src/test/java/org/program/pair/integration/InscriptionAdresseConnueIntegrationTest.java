package org.program.pair.integration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-13 (D8 option A) — une inscription sur une adresse connue garde son 409
 * {@code EMAIL_EXISTS}, et chaque refus est compté.
 */
class InscriptionAdresseConnueIntegrationTest extends AbstractIntegrationTest {

    private static final String COMPTEUR = "auth.register.email_exists";

    @Autowired MeterRegistry registre;

    @Test
    void uneAdresseConnue_rend409EmailExists_etIncrementeLeCompteur() {
        String email = uniqueEmail("deja-connue");
        inscrire(email).expectStatus().isCreated();
        double avant = compte();

        inscrire(email).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("EMAIL_EXISTS");

        // La série est partagée par toute la suite : on mesure l'écart, pas la valeur.
        assertThat(compte() - avant).isEqualTo(1.0);
    }

    @Test
    void uneInscriptionReussie_neCompteRien() {
        double avant = compte();

        inscrire(uniqueEmail("nouvelle")).expectStatus().isCreated();

        assertThat(compte()).isEqualTo(avant);
    }

    @Test
    void leContrat_ecritLeChoixDu409() {
        webTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.paths['/api/auth/register'].post.responses['409'].description")
            .value(d -> assertThat((String) d).contains("EMAIL_EXISTS"))
            .jsonPath("$.paths['/api/auth/register'].post.description")
            .value(d -> assertThat((String) d).contains("révèle"));
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec inscrire(String email) {
        return webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Inscription"))
            .exchange();
    }

    private double compte() {
        Counter compteur = registre.find(COMPTEUR).counter();
        return compteur == null ? 0 : compteur.count();
    }
}
