package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande mobile du 13/09 (inscription, P-MU-28, P-BL-05) — les inscrits d'un
 * créneau voient les prénoms des autres inscrits, et rien de plus.
 *
 * <p>Décor à Grenoble : la base est partagée avec le reste de la suite.
 */
class CoParticipantsIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;

    @Test
    void unInscrit_voitLesAutresInscritsConfirmes_prenomEtAvatarSeulement() {
        Compte hote = compte("Hote Grenoble");
        Compte lea = compte("Léa Martin");
        Compte hugo = compte("Hugo Bernard");
        Compte partie = compte("Nina Retirée");
        UUID creneau = publier(hote);
        rejoindre(lea, creneau);
        rejoindre(hugo, creneau);
        rejoindre(partie, creneau);
        webTestClient.delete().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(partie.token())).exchange().expectStatus().isNoContent();

        List<Map<String, Object>> vus = coParticipants(lea, creneau);

        assertThat(vus).hasSize(1);
        assertThat(vus.get(0)).containsOnlyKeys("userId", "firstName", "avatarUrl");
        assertThat(vus.get(0).get("userId")).isEqualTo(hugo.id().toString());
        assertThat(vus.get(0).get("firstName")).isEqualTo("Hugo");
    }

    @Test
    void unNonInscrit_ouUnePersonneRetiree_recoit403() {
        Compte hote = compte("Hote Bis");
        Compte dehors = compte("Paul Dehors");
        Compte partie = compte("Zoé Partie");
        UUID creneau = publier(hote);
        rejoindre(partie, creneau);
        webTestClient.delete().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(partie.token())).exchange().expectStatus().isNoContent();

        for (Compte refuse : List.of(dehors, partie)) {
            webTestClient.get().uri("/api/slots/{id}/co-participants", creneau)
                .headers(h -> h.setBearerAuth(refuse.token()))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SLOT_PARTICIPANTS_ENROLLED_ONLY");
        }
    }

    @Test
    void apresUnBlocage_chacunDisparaitChezLAutre_etLHoteNeVoitQueLesConfirmes() {
        Compte hote = compte("Hote Ter");
        Compte lea = compte("Léa Bloqueuse");
        Compte hugo = compte("Hugo Bloqué");
        Compte ines = compte("Inès Témoin");
        Compte partie = compte("Max Parti");
        UUID creneau = publier(hote);
        for (Compte c : List.of(lea, hugo, ines, partie)) {
            rejoindre(c, creneau);
        }
        webTestClient.delete().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(partie.token())).exchange().expectStatus().isNoContent();

        webTestClient.post().uri("/api/users/{id}/block", hugo.id())
            .headers(h -> h.setBearerAuth(lea.token()))
            .exchange().expectStatus().isNoContent();

        assertThat(coParticipants(lea, creneau)).extracting(m -> m.get("userId"))
            .containsExactly(ines.id().toString());
        assertThat(coParticipants(hugo, creneau)).extracting(m -> m.get("userId"))
            .containsExactly(ines.id().toString());

        List<?> vusParLHote = webTestClient.get().uri("/api/slots/{id}/participants", creneau)
            .headers(h -> h.setBearerAuth(hote.token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
        assertThat(vusParLHote).as("les seuls CONFIRMED : le retiré n'y est plus").hasSize(3);
    }

    // — décor —

    private record Compte(UUID id, String token) {}

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coParticipants(Compte qui, UUID creneau) {
        return webTestClient.get().uri("/api/slots/{id}/co-participants", creneau)
            .headers(h -> h.setBearerAuth(qui.token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
    }

    private void rejoindre(Compte qui, UUID creneau) {
        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(qui.token()))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private UUID publier(Compte hote) {
        Instant debut = Instant.now().plus(3, ChronoUnit.DAYS);
        Map<String, Object> corps = new HashMap<>();
        corps.put("activityId", activityRepository.findAll().get(0).getId().toString());
        corps.put("startsAt", debut.toString());
        corps.put("endsAt", debut.plus(90, ChronoUnit.MINUTES).toString());
        corps.put("placeName", "Parc Paul Mistral");
        corps.put("placeType", "PUBLIC");
        corps.put("lat", 45.1839);
        corps.put("lng", 5.7359);
        corps.put("addressPublic", "Boulevard Jean Pain, Grenoble");
        corps.put("city", "Grenoble");
        corps.put("maxParticipants", 10);
        Map<?, ?> cree = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(hote.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(cree.get("scheduleId")));
    }

    private Compte compte(String nom) {
        String email = uniqueEmail("coinscrits");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", nom))
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
