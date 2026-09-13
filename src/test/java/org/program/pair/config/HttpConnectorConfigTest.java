package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** P-BS-12 — le connecteur HTTP en clair n'existe qu'en développement. */
class HttpConnectorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(HttpConnectorConfig.class);

    /**
     * Le domaine lien.meetdo.fun cible le port 8091 dans Railway : sans ce
     * connecteur sous railway, tous les liens publics rendent 502 (incident du 13/09).
     */
    @Test
    void sousLeProfilRailway_leConnecteurDuDomainePersonnaliseEstDeclare() {
        runner.withPropertyValues("spring.profiles.active=railway")
            .run(contexte -> assertThat(contexte).hasBean("httpConnector"));
    }

    @Test
    void sansProfil_aucunConnecteurHttpAdditionnelNEstDeclare() {
        runner.run(contexte -> assertThat(contexte).doesNotHaveBean("httpConnector"));
    }

    @Test
    void enDeveloppement_leConnecteurEstDeclare() {
        runner.withPropertyValues("spring.profiles.active=dev")
            .run(contexte -> assertThat(contexte).hasBean("httpConnector"));
    }
}
