package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UpdateProfileRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'attaque de sécurité — vérifie la résistance aux injections SQL, XSS,
 * uploads malveillants et rate limiting.
 */
class SecurityInjectionIntegrationTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "'; DROP TABLE users; --",
        "' OR '1'='1",
        "admin'--",
        "1; DELETE FROM users WHERE '1'='1"
    })
    void champEmail_devraitResisterAuxInjectionsSQL(String payload) {
        // Tenter d'enregistrer avec un payload SQL injection dans l'email
        RegisterRequest req = new RegisterRequest(
            payload + "@test.com",
            "Password123!",
            "Test User"
        );

        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            // Ne doit jamais planter le serveur, doit retourner 201 ou 400 proprement
            .expectStatus().value(status ->
                assertThat(status).isIn(
                    HttpStatus.CREATED.value(),
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.CONFLICT.value()
                )
            );

        // Vérifier que la table users existe toujours (pas de DROP réussi)
        // En tentant un login normal - si la table était supprimée, on aurait une erreur 500
        webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest("verification@test.com", "wrong"))
            .exchange()
            .expectStatus().value(status ->
                assertThat(status).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
            );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert('xss')</script>",
        "<img src=x onerror=alert(1)>",
        "javascript:alert(1)",
        "<svg onload=alert(1)>"
    })
    void bioProfil_devraitNeutraliserLeXSS(String payload) {
        // 1. Créer un utilisateur
        String token = registerAndLogin("xsstest@pair.app");

        // 2. Mettre à jour le profil avec un payload XSS
        UpdateProfileRequest updateReq = new UpdateProfileRequest(
            null,
            payload, // bio avec XSS
            null,
            null,
            null,
            null
        );

        webTestClient.put()
            .uri("/api/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateReq)
            .exchange()
            .expectStatus().isOk();

        // 3. Récupérer le profil et vérifier que le XSS a été neutralisé
        UserPrivateDto profile = webTestClient.get()
            .uri("/api/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(profile).isNotNull();
        assertThat(profile.bio()).doesNotContain("<script");
        assertThat(profile.bio()).doesNotContain("onerror");
        assertThat(profile.bio()).doesNotContain("onload");
        assertThat(profile.bio()).doesNotContain("javascript:");
    }

    @Test
    void uploadAvatar_devraitRejeter_fichierNonImage() {
        String token = registerAndLogin("upload@pair.app");

        // Créer un faux fichier .exe déguisé en .jpg
        byte[] fakeExeAsJpg = new byte[] {
            (byte) 0x4D, (byte) 0x5A, (byte) 0x90, 0x00, 0x03 // header .exe "MZ"
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(fakeExeAsJpg) {
            @Override
            public String getFilename() {
                return "avatar.jpg";
            }
        }).contentType(MediaType.IMAGE_JPEG); // Content-Type mensonger

        webTestClient.post()
            .uri("/api/media/upload/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void uploadAvatar_devraitRejeter_fichierTropVolumineux() {
        String token = registerAndLogin("bigfile@pair.app");

        // Créer un fichier de 6 MB (> limite de 5MB)
        byte[] bigFile = new byte[6 * 1024 * 1024];
        // Remplir avec des données PNG valides (header PNG)
        bigFile[0] = (byte) 0x89;
        bigFile[1] = 0x50;
        bigFile[2] = 0x4E;
        bigFile[3] = 0x47;

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bigFile) {
            @Override
            public String getFilename() {
                return "big.jpg";
            }
        }).contentType(MediaType.IMAGE_JPEG);

        webTestClient.post()
            .uri("/api/media/upload/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void rateLimiting_devraitBloquer_apresTropDeTentativesLogin() {
        // Effectuer 10 tentatives de login échouées
        for (int i = 0; i < 10; i++) {
            webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("inconnu@pair.app", "wrong"))
                .exchange()
                .expectStatus().value(status ->
                    assertThat(status).isIn(
                        HttpStatus.UNAUTHORIZED.value(),
                        HttpStatus.NOT_FOUND.value(),
                        HttpStatus.TOO_MANY_REQUESTS.value()
                    )
                );
        }

        // La 11e tentative doit être bloquée par rate limiting
        webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest("inconnu@pair.app", "wrong"))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ===================== Helper Methods =====================

    /**
     * Enregistre et connecte un utilisateur, retourne son access token.
     */
    private String registerAndLogin(String email) {
        // 1. Enregistrement
        RegisterRequest registerReq = new RegisterRequest(
            email,
            "Password123!",
            email.split("@")[0] // displayName = partie avant @
        );

        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        // 2. Login
        LoginRequest loginReq = new LoginRequest(email, "Password123!");

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        assertThat(authResponse.accessToken()).isNotBlank();

        return authResponse.accessToken();
    }
}
