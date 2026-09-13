package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.1 — avec un port de gestion séparé, comme sous railway, la santé et la
 * version répondent encore sur le port public, et rien d'autre d'actuator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0",
        "management.endpoints.web.exposure.include=health,info,prometheus"})
class SantePubliqueIntegrationTest extends AbstractIntegrationTest {

    @Test
    void laSante_repondSurLePortPublic_sansDetail() {
        webTestClient.get().uri("/actuator/health").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components").doesNotExist();
    }

    @Test
    void laSondeDeDisponibilite_repondSurLePortPublic() {
        webTestClient.get().uri("/actuator/health/readiness").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
        webTestClient.get().uri("/actuator/health/liveness").exchange()
            .expectStatus().isOk();
    }

    @Test
    void laVersion_repondSurLePortPublic() {
        webTestClient.get().uri("/actuator/info").exchange()
            .expectStatus().isOk();
    }

    @Test
    void prometheus_resteFermeSurLePortPublic() {
        webTestClient.get().uri("/actuator/prometheus").exchange()
            .expectStatus().value(code -> org.assertj.core.api.Assertions.assertThat(code).isIn(401, 403, 404));
    }
}
