package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-14 et P-BA-14 — les routes de profil exigent une session, respectent le
 * blocage, et bornent leur pagination.
 *
 * <p>Badges, recommandations et avis d'une personne se lisaient sans session :
 * sans appelant, aucun blocage ne pouvait s'y appliquer. Et leur {@code size}
 * passait tel quel à la base.
 */
class RoutesProfilSousSessionIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;

    @Test
    void lesRecommandationsDUnUtilisateur_neSeLisentPlusSansSession() {
        Compte cible = compte("sans-session");

        webTestClient.get().uri("/api/recommendations/users/{id}", cible.id())
            .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/badges/users/{id}", cible.id())
            .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/reviews/programs/{id}", UUID.randomUUID())
            .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void lesRecommandationsDUnePersonneQuiMABloque_sontIntrouvables() {
        Compte moi = compte("bloque-moi");
        Compte bloqueur = compte("bloqueur");
        bloquer(bloqueur, moi);

        webTestClient.get().uri("/api/recommendations/users/{id}", bloqueur.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/recommendations/stats/{id}", bloqueur.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void lesBadgesDUnePersonneQueJAiBloquee_sontIntrouvables() {
        Compte moi = compte("bloque-badges");
        Compte bloquee = compte("bloquee");
        bloquer(moi, bloquee);

        webTestClient.get().uri("/api/badges/users/{id}", bloquee.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void sansBlocage_lesRoutesDeProfilRestentLisiblesEnSession() {
        Compte moi = compte("lecteur");
        Compte autre = compte("lu");

        webTestClient.get().uri("/api/recommendations/users/{id}", autre.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/badges/users/{id}", autre.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/reviews/programs/{id}", UUID.randomUUID())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();
    }

    @Test
    void uneTailleDePageDemesuree_estRameneeAuPlafond_etLaPageLeDit() {
        Compte moi = compte("taille");

        webTestClient.get().uri("/api/reviews/programs/{id}?size=100000", UUID.randomUUID())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.page.size").isEqualTo(50);
    }

    @Test
    void leCatalogueDemandeAMille_rendCinquanteElements_etUnePageQuiLAnnonce() {
        // Non-régression de l'app publiée, qui demande size=1000 (P-MA-17).
        webTestClient.get().uri("/api/activities?size=1000")
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.size").isEqualTo(50)
            .jsonPath("$.content.length()").value(n -> assertThat((Integer) n).isLessThanOrEqualTo(50));
    }

    @Test
    void unNumeroDePageNegatif_estRefuseEn400() {
        Compte moi = compte("negatif");

        webTestClient.get().uri("/api/reviews/me?page=-1")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_PARAMETER");
    }

    private void bloquer(Compte qui, Compte cible) {
        webTestClient.post().uri("/api/users/{id}/block", cible.id())
            .headers(h -> h.setBearerAuth(qui.token()))
            .exchange().expectStatus().isNoContent();
    }

    private record Compte(UUID id, String token) {}

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Profil"))
            .exchange().expectStatus().isCreated();
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return new Compte(userRepository.findByEmail(email).orElseThrow().getId(), auth.accessToken());
    }
}
