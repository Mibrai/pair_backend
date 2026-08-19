package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.guidelines.dto.GuidelinesStateDto;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot A5 — acceptation des règles de communauté.
 *
 * <p>Le serveur ne porte pas le texte, seulement la version et la trace. Ce sont
 * donc la redemande et l'idempotence qui se vérifient ici : tout le reste vit
 * dans l'application.
 */
class CommunityGuidelinesIntegrationTest extends AbstractIntegrationTest {

    @Value("${pair.guidelines.current-version:1.0}")
    private String currentVersion;

    @Test
    void unCompteNeuf_doitDevoirAccepter() {
        // Aucun rétro-remplissage : c'est l'acceptation explicite qui est demandée,
        // et prétendre qu'elle a eu lieu viderait la fonctionnalité de son sens.
        String token = registerAndLogin();

        GuidelinesStateDto state = getState(token);

        assertThat(state.currentVersion()).isEqualTo(currentVersion);
        assertThat(state.acceptedVersion()).isNull();
        assertThat(state.acceptedAt()).isNull();
        assertThat(state.acceptanceRequired()).isTrue();
    }

    @Test
    void accepter_doitLeverLaDemande() {
        String token = registerAndLogin();

        GuidelinesStateDto state = accept(token, currentVersion);

        assertThat(state.acceptedVersion()).isEqualTo(currentVersion);
        assertThat(state.acceptedAt()).isNotNull();
        assertThat(state.acceptanceRequired()).isFalse();
    }

    @Test
    void reAccepter_neDoitPasReecrireLaDate() {
        // C'est la première acceptation de ce texte-là qui a une valeur. La
        // réécrire à chaque relance ferait dépendre la trace du nombre d'essais.
        String token = registerAndLogin();

        var first = accept(token, currentVersion).acceptedAt();
        var second = accept(token, currentVersion).acceptedAt();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void uneVersionPerimee_doitEtreRefusee_avecUnCodeExploitable() {
        // Le cas normal : une application restée sur un texte ancien. Elle doit
        // pouvoir relire l'état et réafficher le bon texte, ce qu'un code
        // générique ne lui permettrait pas.
        String token = registerAndLogin();

        webTestClient.post()
            .uri("/api/users/me/guidelines/accept")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", "0.1"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("GUIDELINES_VERSION_MISMATCH");

        assertThat(getState(token).acceptanceRequired()).isTrue();
    }

    @Test
    void leProfil_doitPorterLaDemande_desLePremierAppel() {
        // Même raison que pour l'onboarding : un second appel au démarrage se voit.
        String token = registerAndLogin();

        UserPrivateDto before = profile(token);
        assertThat(before.guidelinesAcceptanceRequired()).isTrue();
        assertThat(before.guidelinesVersion()).isNull();

        accept(token, currentVersion);

        UserPrivateDto after = profile(token);
        assertThat(after.guidelinesAcceptanceRequired()).isFalse();
        assertThat(after.guidelinesVersion()).isEqualTo(currentVersion);
    }

    // — helpers —

    private GuidelinesStateDto getState(String token) {
        return webTestClient.get()
            .uri("/api/users/me/guidelines")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(GuidelinesStateDto.class).returnResult().getResponseBody();
    }

    private GuidelinesStateDto accept(String token, String version) {
        return webTestClient.post()
            .uri("/api/users/me/guidelines/accept")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", version))
            .exchange().expectStatus().isOk()
            .expectBody(GuidelinesStateDto.class).returnResult().getResponseBody();
    }

    private UserPrivateDto profile(String token) {
        return webTestClient.get()
            .uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(UserPrivateDto.class).returnResult().getResponseBody();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("guidelines");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Nouveau"))
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
