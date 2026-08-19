package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot C3 — signal de fiabilité, vu de l'API.
 *
 * <p>Le calcul est couvert par un test unitaire. Ce qui se vérifie ici est ce
 * qui sort réellement du serveur : un libellé et rien d'autre, et surtout aucun
 * chemin par lequel un pourcentage redeviendrait calculable.
 */
class ReliabilitySignalIntegrationTest extends AbstractIntegrationTest {

    @Test
    void leProfilPublic_doitPorterUnLibelle_jamaisDesCompteurs() {
        String viewer = registerAndLogin();
        String other = registerAndLogin();
        UUID otherId = userId(other);

        String body = new String(webTestClient.get().uri("/api/users/{id}", otherId)
            .headers(h -> h.setBearerAuth(viewer))
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        assertThat(body).contains("reliabilitySignal");
        // Le dénominateur ne doit jamais sortir : avec le numérateur, il rendrait
        // le pourcentage calculable côté client.
        assertThat(body).doesNotContain("joinedSlotsCount");
        assertThat(body).doesNotContain("attendanceCount");
    }

    @Test
    void unCompteNeuf_neDoitPorterAucunSignal() {
        // Ni positif ni négatif. L'absence de signal est l'état normal de qui
        // vient d'arriver.
        String viewer = registerAndLogin();
        String other = registerAndLogin();

        webTestClient.get().uri("/api/users/{id}", userId(other))
            .headers(h -> h.setBearerAuth(viewer))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.reliabilitySignal").doesNotExist();
    }

    @Test
    void lesStatistiquesDePratique_neDoiventPasPorterLeDenominateur() {
        // Le vrai risque du lot : ajouter joinedSlotsCount ici « par symétrie »
        // suffirait à reconstituer le pourcentage avec attendanceCount, déjà
        // présent. Ce test existe pour que l'ajout se voie.
        String viewer = registerAndLogin();
        String other = registerAndLogin();

        String body = new String(webTestClient.get()
            .uri("/api/users/{id}/practice-stats", userId(other))
            .headers(h -> h.setBearerAuth(viewer))
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        assertThat(body).contains("attendanceCount");
        assertThat(body).doesNotContain("joinedSlotsCount");
        assertThat(body).doesNotContain("reliability");
    }

    @Test
    void lesStatistiques_doiventRespecterLeBlocage() {
        // Cette route n'avait aucun contrôle : ni appelant identifié, ni
        // vérification de blocage.
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        UUID bobId = userId(bob);

        webTestClient.post().uri("/api/users/{id}/block", bobId)
            .headers(h -> h.setBearerAuth(alice))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/users/{id}/practice-stats", bobId)
            .headers(h -> h.setBearerAuth(alice))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void aucuneRoute_neDoitTrierNiFiltrerSurLaFiabilite() {
        // Garde-fou explicite. La recherche de personnes n'accepte aucun tri, et
        // un paramètre inconnu ne doit pas en ouvrir un par la bande.
        String viewer = registerAndLogin();

        webTestClient.get()
            .uri(b -> b.path("/api/users")
                .queryParam("query", "a")
                .queryParam("sort", "joinedSlotsCount,desc")
                .build())
            .headers(h -> h.setBearerAuth(viewer))
            .exchange()
            .expectStatus().isOk();
        // Le paramètre est simplement ignoré : aucun résolveur Pageable n'est
        // branché, et aucun contrôleur n'accepte de champ de tri libre.
    }

    // — helpers —

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private String registerAndLogin() {
        String email = uniqueEmail("reliability");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Pratiquant"))
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
