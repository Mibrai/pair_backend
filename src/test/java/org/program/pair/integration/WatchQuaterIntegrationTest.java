package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.outbox.OutboxDelivery;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le retour QUATER : {@code alertDelivery} sur la liste active (§1) et la liste
 * des arrivées attendues d'un organisateur (§2).
 */
class WatchQuaterIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void activesPorteAlertDelivery_etVoitLeRebond() {
        Compte moi = compte();
        UUID watchId = escalader(moi);

        // §1 : le champ est présent sur la liste active.
        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[0].alertDelivery").exists();

        // Un rebond marqué sur le message d'alerte doit remonter jusqu'à la liste.
        OutboxMessage alerte = outboxRepository.findByWatchId(watchId).get(0);
        alerte.setDeliveryState(OutboxDelivery.BOUNCED);
        outboxRepository.saveAndFlush(alerte);

        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[0].alertDelivery").isEqualTo("BOUNCED");
    }

    @Test
    void pendingArrivals_reserveALorganisateur_etFermeATroisChamps() {
        Compte hote = compte();
        UUID scheduleId = creerCreneau(hote);
        // L'hôte, inscrit à son propre créneau, arme sa veille (sans arriver).
        UUID guardianId = contactAccepte(hote);
        String watchId = String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(hote.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        byte[] raw = webTestClient.get().uri("/api/schedules/{s}/pending-arrivals", scheduleId)
            .headers(h -> h.setBearerAuth(hote.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].watchId").isEqualTo(watchId)
            .jsonPath("$[0].name").exists()
            .jsonPath("$[0].since").exists()
            .returnResult().getResponseBodyContent();
        // Fermé à trois champs : rien du reste ne fuit.
        String body = new String(raw);
        assertThat(body)
            .doesNotContain("guardianId")
            .doesNotContain("deadlineAt")
            .doesNotContain("48.57");

        // Un compte qui n'organise pas ce créneau : introuvable, pas interdit.
        Compte etranger = compte();
        webTestClient.get().uri("/api/schedules/{s}/pending-arrivals", scheduleId)
            .headers(h -> h.setBearerAuth(etranger.token()))
            .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ outils

    private UUID escalader(Compte owner) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner);
        UUID watchId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();
        Watch w = watchRepository.findById(watchId).orElseThrow();
        w.setDeadlineAt(Instant.now().minus(90, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }
        return watchId;
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
        String email = uniqueEmail("quater");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Qua" + UUID.randomUUID().toString().substring(0, 8)))
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
