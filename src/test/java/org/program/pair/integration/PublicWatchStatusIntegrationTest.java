package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEventType;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La page de statut publique : les six états, l'ETag, les accusés, l'expiration,
 * la révocation, et le gate « si le principal n'a rien ouvert » du contact de
 * secours.
 */
class PublicWatchStatusIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired WatchEventRepository eventRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void uneVeilleEscaladee_afficheAlerteEnvoyee_avecUnEtag() {
        Compte moi = compte();
        UUID watchId = escalader(moi, null);
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();
        assertThat(token).isNotNull();

        String html = new String(webTestClient.get().uri("/public/watch/{t}", token)
            .exchange().expectStatus().isOk()
            .expectHeader().exists("ETag")
            .expectHeader().valueEquals("Cache-Control", "max-age=20")
            .expectBody().returnResult().getResponseBodyContent());
        assertThat(html)
            .contains("Alerte envoyée")
            .contains("112")
            .contains("J'ai vu")
            .contains("Je l'ai eue au téléphone")
            // Aucun bouton de clôture : la page est publique.
            .doesNotContainIgnoringCase("clôturer")
            .doesNotContainIgnoringCase("clore la veille");
    }

    @Test
    void unMemeEtag_rend304() {
        Compte moi = compte();
        UUID watchId = escalader(moi, null);
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();

        String etag = webTestClient.get().uri("/public/watch/{t}", token)
            .exchange().expectStatus().isOk()
            .returnResult(Void.class).getResponseHeaders().getETag();

        webTestClient.get().uri("/public/watch/{t}", token)
            .header("If-None-Match", etag)
            .exchange().expectStatus().isNotModified();
    }

    @Test
    void lAccuseJaiVu_remonteDansLaChronologie() {
        Compte moi = compte();
        UUID watchId = escalader(moi, null);
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();

        webTestClient.post().uri("/public/watch/{t}/seen", token)
            .exchange().expectStatus().is3xxRedirection();

        assertThat(eventRepository.existsByWatchIdAndType(watchId, WatchEventType.GUARDIAN_ACK_SEEN)).isTrue();
        assertThat(watchRepository.findById(watchId).orElseThrow().getPublicViewedAt()).isNotNull();
    }

    @Test
    void unLienRevoque_devientIntrouvable() {
        Compte moi = compte();
        UUID watchId = escalader(moi, null);
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();

        webTestClient.post().uri("/api/watches/{id}/revoke-link", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/public/watch/{t}", token)
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void unJetonInconnu_rendUnePageIntrouvable() {
        webTestClient.get().uri("/public/watch/{t}", "jetonQuiNexistePas00")
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void siLePrincipalAOuvertLaPage_leSecoursNestPasPrevenu() {
        Compte moi = compte();
        UUID watchId = escalader(moi, "0655667788"); // avec contact de secours
        String token = watchRepository.findById(watchId).orElseThrow().getPublicToken();

        // Le principal ouvre la page — cela vaut réponse.
        webTestClient.get().uri("/public/watch/{t}", token).exchange().expectStatus().isOk();

        // On avance jusqu'à la fenêtre du secours et on repasse le minuteur.
        reculerEcheance(watchId, 90);
        returnLoopJob.tick();

        assertThat(eventRepository.existsByWatchIdAndType(watchId, WatchEventType.BACKUP_ALERTED))
            .as("le secours ne doit pas être prévenu si le principal a ouvert")
            .isFalse();
    }

    // ------------------------------------------------------------------ outils

    /** Arme, valide l'arrivée, puis pousse jusqu'à l'escalade. backupPhone facultatif. */
    private UUID escalader(Compte owner, String backupPhone) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner, "0612345678", "principal@example.org");
        var body = new java.util.HashMap<String, Object>();
        body.put("scheduleId", scheduleId.toString());
        body.put("guardianId", guardianId.toString());
        if (backupPhone != null) {
            UUID backup = contactAccepte(owner, backupPhone, "secours@example.org");
            body.put("backupGuardianId", backup.toString());
        }
        UUID watchId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();

        // 62 min : au-dessus du seuil d'escalade (+60), mais sous la fenêtre du
        // contact de secours (+75) — l'escalade part, le secours pas encore.
        reculerEcheance(watchId, 62);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }
        return watchId;
    }

    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watchRepository.findById(watchId).orElseThrow();
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
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
        String email = uniqueEmail("pubwatch");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Pub" + UUID.randomUUID().toString().substring(0, 8)))
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
