package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-15 — le motif de blocage est borné à 30 caractères, et la borne tient.
 *
 * <p>{@code @Size(max = 30)} était posé sur {@code BlockRequest} mais inerte : le
 * contrôleur ne portait pas {@code @Valid}. Le corps reste facultatif — l'app
 * bloque sans en envoyer.
 */
class BlockValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;

    @Test
    void unMotifDe31Caracteres_doitRendre400() {
        String blocker = registerAndLogin(uniqueEmail("block-long"));
        UUID target = userIdOf(uniqueEmail("block-long-target"));

        webTestClient.post().uri("/api/users/{id}/block", target)
            .headers(h -> h.setBearerAuth(blocker))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "x".repeat(31)))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bloquerSansCorps_resteRendu204() {
        String blocker = registerAndLogin(uniqueEmail("block-empty"));
        UUID target = userIdOf(uniqueEmail("block-empty-target"));

        webTestClient.post().uri("/api/users/{id}/block", target)
            .headers(h -> h.setBearerAuth(blocker))
            .exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void unMotifDe30Caracteres_resteAccepte() {
        String blocker = registerAndLogin(uniqueEmail("block-ok"));
        UUID target = userIdOf(uniqueEmail("block-ok-target"));

        webTestClient.post().uri("/api/users/{id}/block", target)
            .headers(h -> h.setBearerAuth(blocker))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "x".repeat(30)))
            .exchange()
            .expectStatus().isNoContent();
    }

    private UUID userIdOf(String email) {
        registerAndLogin(email);
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private String registerAndLogin(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Bloqueur"))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
