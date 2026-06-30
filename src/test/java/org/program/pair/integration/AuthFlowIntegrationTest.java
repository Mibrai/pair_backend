package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void parcoursComplet_inscriptionVerificationConnexion() {
        // 1. Inscription
        RegisterRequest registerReq = new RegisterRequest(
            "nouveau@pair.app", "MotDePasse123!", "Nouvel Utilisateur");

        AuthResponse registerResp = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(registerResp).isNotNull();
        assertThat(registerResp.verificationStatus()).isEqualTo("UNVERIFIED");
        assertThat(registerResp.accessToken()).isNotBlank();
        assertThat(registerResp.refreshToken()).isNotBlank();

        // 2. Login immédiat possible même non vérifié
        LoginRequest loginReq = new LoginRequest("nouveau@pair.app", "MotDePasse123!");

        AuthResponse loginResp = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(loginResp).isNotNull();
        assertThat(loginResp.accessToken()).isNotBlank();

        // 3. Profil accessible avec le token
        UserPrivateDto profileResp = webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(loginResp.accessToken()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult().getResponseBody();

        assertThat(profileResp).isNotNull();
        assertThat(profileResp.email()).isEqualTo("nouveau@pair.app");
        assertThat(profileResp.displayName()).isEqualTo("Nouvel Utilisateur");
    }

    @Test
    void register_devraitRetourner409_siEmailDejaUtilise() {
        RegisterRequest req = new RegisterRequest(
            "doublon@pair.app", "Password123!", "User1");

        // Premier enregistrement
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class);

        // Tentative de doublon
        ErrorResponse errorResp = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(errorResp).isNotNull();
        assertThat(errorResp.code()).isNotBlank();
    }

    @Test
    void accesSansToken_devraitRetourner401() {
        webTestClient.get()
            .uri("/api/users/me")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void accesAvecTokenInvalide_devraitRetourner401() {
        webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth("token.invalide.xyz"))
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
