package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** P-BS-12 — le connecteur HTTP en clair n'existe qu'en développement. */
class HttpConnectorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(HttpConnectorConfig.class);

    @Test
    void sousLeProfilRailway_aucunConnecteurHttpAdditionnelNEstDeclare() {
        runner.withPropertyValues("spring.profiles.active=railway")
            .run(contexte -> assertThat(contexte).doesNotHaveBean("httpConnector"));
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
