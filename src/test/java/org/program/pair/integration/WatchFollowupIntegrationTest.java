package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le retour du chantier mobile côté veille : le lien public exposé à la
 * propriétaire (§2), l'entier {@code attemptsLeft} (§4.1), {@code seen-by-host}
 * (§1a), le journal (§1b) et l'état de remise (§6).
 */
class WatchFollowupIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void laVeilleExposeSonLienPublic_etLesAccuses_apresLescalade() {
        Compte moi = compte();
        UUID watchId = escalader(moi);
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();

        // §2 : le jeton et l'URL sont rendus, plus l'état de remise (§6).
        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.publicToken").isEqualTo(token)
            .jsonPath("$.watch.publicStatusUrl").value(u -> assertThat((String) u).contains(token))
            .jsonPath("$.watch.guardianSeenAt").doesNotExist()
            .jsonPath("$.alertDelivery").exists();

        // Le contact clique « j'ai vu » sur la page publique.
        webTestClient.post().uri("/public/watch/{t}/seen", token)
            .exchange().expectStatus().is3xxRedirection();

        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.watch.guardianSeenAt").exists();
    }

    @Test
    void leMauvaisCode_rendAttemptsLeftEntier() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", "ZZZZZ", "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("WATCH_CODE_WRONG")
            .jsonPath("$.attemptsLeft").isEqualTo(2);
    }

    @Test
    void leJournal_rendLesVeillesTerminees_sansCoordonnees() {
        Compte moi = compte();
        UUID watchId = veilleArmee(moi);
        String code = arriver(moi, watchId);
        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        byte[] raw = webTestClient.get().uri("/api/watches/history")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].state").isEqualTo("CLOSED")
            .jsonPath("$[0].placeName").isEqualTo("Studio Lumière")
            .returnResult().getResponseBodyContent();
        // Aucune coordonnée dans le journal : ni latitude, ni champ lat/lng.
        assertThat(new String(raw)).doesNotContain("48.57").doesNotContain("\"lat\"");
    }

    @Test
    void seenByHost_repousseLaRelance_etRefuseUnNonOrganisateur() {
        Compte hote = compte();
        UUID watchId = veilleArmee(hote); // l'hôte arme sa propre veille sur son créneau
        Instant baseAvant = watchRepository.findById(watchId).orElseThrow().getOutboundBaseAt();

        // L'organisateur (ici l'hôte lui-même) : la relance est repoussée de 15 min.
        webTestClient.post().uri("/api/watches/{id}/seen-by-host", watchId)
            .headers(h -> h.setBearerAuth(hote.token()))
            .exchange().expectStatus().isNoContent();
        assertThat(watchRepository.findById(watchId).orElseThrow().getOutboundBaseAt())
            .isEqualTo(baseAvant.plus(15, ChronoUnit.MINUTES));

        // Un compte qui n'organise pas ce créneau : introuvable, pas interdit.
        Compte etranger = compte();
        webTestClient.post().uri("/api/watches/{id}/seen-by-host", watchId)
            .headers(h -> h.setBearerAuth(etranger.token()))
            .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ outils

    private UUID escalader(Compte owner) {
        UUID watchId = surPlace(owner);
        Watch w = watchRepository.findById(watchId).orElseThrow();
        w.setDeadlineAt(Instant.now().minus(90, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }
        return watchId;
    }

    /** Arme et valide l'arrivée ; rend l'id de la veille. */
    private UUID surPlace(Compte owner) {
        UUID watchId = veilleArmee(owner);
        arriver(owner, watchId);
        return watchId;
    }

    /** Valide l'arrivée et rend le code de retour. */
    private String arriver(Compte owner, UUID watchId) {
        return String.valueOf(webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
    }

    private UUID veilleArmee(Compte owner) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner);
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID creerCreneau(Compte owner) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.HOURS), null,
                "Studio Lumière", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    private UUID contactAccepte(Compte owner) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "email", "proche@example.org"))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("followup");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Fol" + UUID.randomUUID().toString().substring(0, 8)))
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
