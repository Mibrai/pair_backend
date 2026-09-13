package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-11 — un refus sans code nommé garde son code générique et parle la langue
 * du client. « Programme introuvable. » partait en français à un client allemand.
 */
class RefusTraduitsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unProgrammeIntrouvable_repondDansLaLangueDuClient_sansChangerDeCode() {
        String token = compte();
        UUID inconnu = UUID.randomUUID();

        for (String[] cas : new String[][] {
                {"fr", "Programme introuvable."},
                {"en", "Program not found."},
                {"de", "Programm nicht gefunden."}}) {
            webTestClient.get().uri("/api/programs/{id}", inconnu)
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("Accept-Language", cas[0]);
                })
                .exchange().expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.message").isEqualTo(cas[1]);
        }
    }

    private String compte() {
        String email = uniqueEmail("refus-traduits");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Traduit"))
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
