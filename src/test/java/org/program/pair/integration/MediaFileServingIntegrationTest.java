package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit et couvre le bug de prod : GET /api/media/files/** renvoyait 500 pour tout
 * média uploadé (avatars et images de programme), à cause de deux défauts cumulés :
 * 1. MediaController#serveFile ne savait pas extraire le chemin de la requête ("**"
 *    wildcard non exploité, extractPath() levait toujours UnsupportedOperationException) ;
 * 2. même corrigé, GlobalExceptionHandler interceptait toute ResponseStatusException via
 *    son handler générique Exception.class et la renvoyait en 500, quel que soit le status
 *    voulu par l'exception (donc même un 404 légitime devenait un 500).
 */
class MediaFileServingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void uploadPuisLecture_devraitRetourner200EtLImage() throws IOException {
        String token = registerAndLogin("media-read@pair.app");

        UserPrivateDto profile = webTestClient.post()
            .uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(pngUploadBody().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(profile).isNotNull();
        assertThat(profile.avatarUrl()).isNotBlank();
        assertThat(profile.avatarUrl()).startsWith("/api/media/files/user_avatar/");

        byte[] body = webTestClient.get()
            .uri(profile.avatarUrl())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().value(HttpHeaders.CONTENT_TYPE, ct -> assertThat(ct).startsWith("image/"))
            .expectBody(byte[].class)
            .returnResult()
            .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.length).isGreaterThan(0);
    }

    @Test
    void lectureSansToken_devraitRetourner401() throws IOException {
        String token = registerAndLogin("media-noauth@pair.app");

        UserPrivateDto profile = webTestClient.post()
            .uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(pngUploadBody().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(profile).isNotNull();

        webTestClient.get()
            .uri(profile.avatarUrl())
            .exchange()
            .expectStatus().isUnauthorized();
    }

    /**
     * Le 404 ne suffit pas : il lui faut un <b>code</b>. Sans lui, le refus ne
     * portait qu'un {@code message} anglais (« File not found »), que le client
     * — qui traduit par code — n'avait d'autre choix que d'afficher tel quel à
     * un utilisateur francophone.
     */
    @Test
    void lectureFichierInexistant_devraitRetourner404EtPas500() {
        String token = registerAndLogin("media-missing@pair.app");

        webTestClient.get()
            .uri("/api/media/files/user_avatar/" + UUID.randomUUID() + ".png")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MEDIA_FILE_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("Ce fichier n'est plus disponible.");
    }

    @Test
    void lectureFichierInexistant_devraitTraduireLeMessage_selonAcceptLanguage() {
        String token = registerAndLogin("media-missing-en@pair.app");

        webTestClient.get()
            .uri("/api/media/files/user_avatar/" + UUID.randomUUID() + ".png")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MEDIA_FILE_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("This file is no longer available.");
    }

    private MultipartBodyBuilder pngUploadBody() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(validPngBytes()) {
            @Override
            public String getFilename() {
                return "avatar.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder;
    }

    private byte[] validPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String registerAndLogin(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);

        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

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
