package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchOutboundJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.IncidentRepository;
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
 * La boucle aller : les demandes « tu y es ? », le « je suis en chemin » qui les
 * repousse, l'abandon, et le « perdu en chemin » qui journalise un incident sans
 * jamais compter d'absence.
 *
 * <p>Le test qui porte le garde-fou du §6 est
 * {@link #perduEnChemin_journaliseUnIncident_jamaisUneAbsence()} : un incident de
 * sécurité ne doit pas se muer en reproche, sans quoi la personne désarme la
 * veille la fois d'après.
 */
class WatchOutboundIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired WatchOutboundJob outboundJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void troisDemandesSansArrivee_puisPerduEnChemin() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0612345678", "aller@example.org");
        reculerBaseAller(watchId, 60); // 60 min après le début : tout est dû.

        outboundJob.tick();
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.EN_ROUTE);
        assertThat(watch(watchId).getArrivalPromptsSent()).isEqualTo(1);
        // Pas encore d'alerte : l'étiquette ne se pose qu'à la troisième demande.
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();

        outboundJob.tick();
        outboundJob.tick();
        assertThat(watch(watchId).getArrivalPromptsSent()).isEqualTo(3);
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();

        // Quatrième passage : perdu en chemin, message ⑤ au contact.
        outboundJob.tick();
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.ESCALATED);
        assertThat(outboxRepository.findByWatchId(watchId))
            .anySatisfy(m -> assertThat(m.getBody()).contains("n'est pas arrivée"));
    }

    @Test
    void perduEnChemin_journaliseUnIncident_jamaisUneAbsence() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0698112233", "incident@example.org");
        reculerBaseAller(watchId, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }

        // Un incident TRANSIT est écrit...
        assertThat(incidentRepository.existsByWatchId(watchId)).isTrue();
        // ...et AUCUNE ligne Attendance : un perdu en chemin ne pèse pas contre la fiabilité.
        assertThat(attendanceRepository.existsByScheduleIdAndUserId(
            watch(watchId).getScheduleId(), moi.id())).isFalse();
    }

    @Test
    void jeSuisEnChemin_repousseLaRelanceDeQuinzeMinutes() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0611998877", "enchemin@example.org");
        Instant baseAvant = watch(watchId).getOutboundBaseAt();

        webTestClient.post().uri("/api/watches/{id}/still-coming", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("EN_ROUTE");

        assertThat(watch(watchId).getOutboundBaseAt())
            .isEqualTo(baseAvant.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void jeNyVaisPas_fermeSansMessageNiAbsence() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0655443322", "abandon@example.org");

        webTestClient.post().uri("/api/watches/{id}/abandon", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("CLOSED");

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(attendanceRepository.existsByScheduleIdAndUserId(
            watch(watchId).getScheduleId(), moi.id())).isFalse();
    }

    @Test
    void cesGestes_neValentQueSurLeTrajetAller() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0644332211", "trajet@example.org");
        // On valide l'arrivée : la veille n'est plus sur le trajet aller.
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();

        webTestClient.post().uri("/api/watches/{id}/still-coming", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NOT_OUTBOUND");
    }

    // ------------------------------------------------------------------ outils

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerBaseAller(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setOutboundBaseAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    private UUID armer(Compte owner, String phone, String email) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner, phone, email);
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

    private UUID contactAccepte(Compte owner, String phone, String email) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", phone, "email", email))
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
        String email = uniqueEmail("aller");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Aller" + UUID.randomUUID().toString().substring(0, 8)))
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
