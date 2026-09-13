package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-18 — aucune origine web n'appelle l'API hors développement (décision du
 * 13/09 : ni localhost, ni l'ancien front Vercel, en production).
 *
 * <p>Le profil test n'a que la configuration commune, donc la liste vide qu'a
 * aussi la production : ce que ce test voit est ce que railway sert.
 */
class CorsProductionIntegrationTest extends AbstractIntegrationTest {

    private static final String CLE = "pair.cors.allowed-origins";

    @Test
    void unPreflightDepuisLocalhost_doitEtreRefuse() {
        webTestClient.method(HttpMethod.OPTIONS).uri("/api/categories")
            .header(HttpHeaders.ORIGIN, "http://localhost:3000")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    @Test
    void uneRequeteDepuisLAncienFrontVercel_doitEtreRefusee() {
        webTestClient.get().uri("/api/categories")
            .header(HttpHeaders.ORIGIN, "https://pair-frontend-omega.vercel.app")
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void unClientNatifSansOrigin_nEstPasConcerne() {
        webTestClient.get().uri("/api/categories")
            .exchange()
            .expectStatus().isOk();
    }

    /** Seul le profil dev pose des origines ; les profils déployés n'en ajoutent aucune. */
    @Test
    void seulLeProfilDev_doitPoserDesOrigines() throws IOException {
        assertThat(charger("application.properties").getProperty(CLE)).isEmpty();
        for (String profil : new String[] {"railway", "prod", "staging"}) {
            assertThat(charger("application-" + profil + ".properties").getProperty(CLE))
                .as("application-%s.properties ne doit poser aucune origine web", profil)
                .isNull();
        }
        assertThat(charger("application-dev.properties").getProperty(CLE))
            .doesNotContain("vercel");
    }

    private static Properties charger(String nom) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource(nom));
    }
}
