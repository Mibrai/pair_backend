package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le registre des incidents, et son unique pont vers la modération.
 *
 * <p>Le test qui porte la décision de séparation est
 * {@link #unIncidentTransit_neVaPasDansLaModeration()} : « perdu en chemin » et
 * « lieu mal éclairé » ne doivent jamais atterrir dans la file des signalements,
 * sinon la victime finit dans la colonne des signalés.
 */
class IncidentIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unIncidentTransit_estEcrit_etVisibleDansMesIncidents() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "TRANSIT", "note", "Je me suis perdue en chemin."))
            .exchange().expectStatus().isCreated()
            .expectBody().jsonPath("$.target").isEqualTo("TRANSIT");

        webTestClient.get().uri("/api/incidents/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].target").isEqualTo("TRANSIT");
    }

    @Test
    void unIncidentTransit_neVaPasDansLaModeration() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu mal éclairé, peu rassurant."))
            .exchange().expectStatus().isCreated();

        // Rien ne doit être apparu dans mes signalements : le registre est séparé.
        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.page.totalElements").isEqualTo(0);
    }

    @Test
    void unIncidentPerson_basculeDansLaModeration() {
        Compte moi = compte();
        Compte autre = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "target", "PERSON",
                "targetUserId", autre.id().toString(),
                "reason", "HARASSMENT",
                "note", "Comportement inapproprié et insistant."))
            .exchange().expectStatus().isCreated();

        // L'incident est dans mon registre...
        webTestClient.get().uri("/api/incidents/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[0].target").isEqualTo("PERSON");

        // ...et un signalement est bien parti en modération.
        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.page.totalElements").isEqualTo(1);
    }

    @Test
    void unIncidentPersonSansCible_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PERSON", "note", "Quelqu'un, mais je ne dis pas qui."))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INCIDENT_PERSON_TARGET_REQUIRED");
    }

    @Test
    void seSignalerSoiMemeEnPerson_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "target", "PERSON",
                "targetUserId", moi.id().toString(),
                "note", "Description assez longue pour valider la règle."))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    // ------------------------------------------------------------------ outils

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("incident");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Inc" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        return new Compte(id, auth.accessToken());
    }
}
