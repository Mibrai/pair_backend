package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
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
 * Les sorties d'une veille sur place : snooze, panic, renvoi de code, interruption.
 */
class WatchExitsIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;
    private static final String MOT_DE_PASSE = "Password123!";

    @Test
    void snooze_repousseDe30Min_etRearmeLesRappels() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);
        Watch avant = watch(watchId);
        // Simule un rappel déjà parti pour vérifier le réarmement.
        avant.setRemindersSent(2);
        watchRepository.saveAndFlush(avant);
        Instant echeanceAvant = watch(watchId).getDeadlineAt();

        webTestClient.post().uri("/api/watches/{id}/snooze", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.remindersSent").isEqualTo(0);

        assertThat(watch(watchId).getDeadlineAt())
            .isEqualTo(echeanceAvant.plus(30, ChronoUnit.MINUTES));
    }

    @Test
    void panic_faitPartirLeMessageImmediatement() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        webTestClient.post().uri("/api/watches/{id}/panic", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("ESCALATED");

        assertThat(outboxRepository.findByWatchId(watchId)).isNotEmpty();
    }

    @Test
    void resendCode_regenereSousMotDePasse_etRefuseLeSecondRenvoi() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        // Mauvais mot de passe : refusé.
        webTestClient.post().uri("/api/watches/{id}/resend-code", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("password", "faux"))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_PASSWORD_REQUIRED");

        // Bon mot de passe : un nouveau code, une fois.
        String nouveau = String.valueOf(webTestClient.post().uri("/api/watches/{id}/resend-code", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("password", MOT_DE_PASSE))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
        assertThat(nouveau).hasSize(5);

        // Le nouveau code referme bien la veille.
        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", nouveau, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();
    }

    @Test
    void resendCode_uneSeuleFoisParCycle() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        webTestClient.post().uri("/api/watches/{id}/resend-code", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("password", MOT_DE_PASSE))
            .exchange().expectStatus().isOk();

        webTestClient.post().uri("/api/watches/{id}/resend-code", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("password", MOT_DE_PASSE))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_RESEND_ALREADY_USED");
    }

    @Test
    void interruptionEnTrajet_recaleLecheanceSurLeTrajet_pasSurMaintenant() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        webTestClient.post().uri("/api/watches/{id}/interrupt", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "Ça se passait mal", "alreadyHome", false, "travelMinutes", 30))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.interruptedAt").exists();

        // L'échéance couvre le trajet de retour — elle n'éteint pas la veille.
        Watch w = watch(watchId);
        assertThat(w.getDeadlineAt()).isAfter(Instant.now().plus(20, ChronoUnit.MINUTES));
        // Statut public « repartie plus tôt » découle de interruptedAt.
        assertThat(w.getInterruptedAt()).isNotNull();
    }

    @Test
    void interruptionDejaRentree_echeanceMaintenant() {
        Compte moi = compte();
        UUID watchId = surPlace(moi);

        webTestClient.post().uri("/api/watches/{id}/interrupt", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("alreadyHome", true))
            .exchange().expectStatus().isOk();

        // Retour à confirmer sur-le-champ : l'échéance est ramenée à maintenant.
        assertThat(watch(watchId).getDeadlineAt()).isBeforeOrEqualTo(Instant.now().plus(5, ChronoUnit.SECONDS));
    }

    // ------------------------------------------------------------------ outils

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    /** Une veille armée puis passée sur place, avec un code de retour créé. */
    private UUID surPlace(Compte owner) {
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
            .bodyValue(Map.of("name", "Proche", "phone", "0612345678", "email", "proche@example.org"))
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
        String email = uniqueEmail("exits");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, MOT_DE_PASSE,
                "Exit" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, MOT_DE_PASSE))
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
