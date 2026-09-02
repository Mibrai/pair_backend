package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'armement d'une veille et son miroir : les quatre routes de la priorité 2.
 *
 * <p>Le test qui porte la dépendance du module est
 * {@link #armerSansContactAccepte_estRefuse()} : sans contact accepté, rien ne
 * s'arme. C'est ce qui relie la priorité 2 à la priorité 1, et ce qui donne son
 * sens à l'ordre de service.
 */
class WatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    // -------------------------------------------------------------------- armer

    @Test
    void armerAvecUnContactAccepte_poseUneVeilleArmee() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.state").isEqualTo("ARMED")
            .jsonPath("$.remindersSent").isEqualTo(0)
            .jsonPath("$.deadlineAt").exists()
            .jsonPath("$.guardianId").isEqualTo(guardianId.toString());
    }

    @Test
    void armerSansContactAccepte_estRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianPending = contactNonAccepte(moi);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianPending.toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_GUARDIAN_NOT_ACCEPTED");
    }

    /**
     * Le même contact aux deux postes est refusé, et non ignoré.
     *
     * <p>Accepté, il sautait la vérification du contact de secours et donnait une
     * <b>seconde ligne de défense qui n'existe pas</b> : à l'escalade, la branche du
     * secours prévenait la même personne une seconde fois et inscrivait
     * {@code BACKUP_ALERTED} à la chronologie. La veille affichait un second recours
     * sollicité alors qu'un seul proche avait été joint, deux fois.
     */
    @Test
    void armerAvecLeMemeContactEnPrincipalEtEnSecours_estRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "guardianId", guardianId.toString(),
                              "backupGuardianId", guardianId.toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_BACKUP_SAME_AS_PRIMARY");
    }

    @Test
    void armerDeuxFoisLeMemeCreneau_estRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);

        armer(moi, scheduleId, guardianId).expectStatus().isCreated();

        armer(moi, scheduleId, guardianId)
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_ALREADY_ACTIVE");
    }

    @Test
    void armerAvecUneEcheanceDansLePasse_estRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "scheduleId", scheduleId.toString(),
                "guardianId", guardianId.toString(),
                "deadlineAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_DEADLINE_PAST");
    }

    @Test
    void armerSurUnCreneauOuLonNestPasInscrit_rend404() {
        Compte hote = compte();
        UUID scheduleId = creerCreneau(hote);

        // Un autre compte, qui n'est ni hôte ni inscrit : le créneau lui est
        // introuvable, pas interdit.
        Compte etranger = compte();
        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(etranger.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                "guardianId", UUID.randomUUID().toString()))
            .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------- lire

    @Test
    void laVeilleFigureDansMesVeillesActives_puisEnDisparaitUneFoisDesarmee() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);
        String watchId = String.valueOf(armer(moi, scheduleId, guardianId)
            .expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(1);

        webTestClient.delete().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void leDetailRendLaChronologie_avecLarmement() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);
        String watchId = String.valueOf(armer(moi, scheduleId, guardianId)
            .expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.state").isEqualTo("ARMED")
            .jsonPath("$.timeline[0].type").isEqualTo("ARMED");
    }

    @Test
    void desarmer_fermeLaVeille_etInscritLaChronologie() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);
        String watchId = String.valueOf(armer(moi, scheduleId, guardianId)
            .expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        webTestClient.delete().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.state").isEqualTo("CLOSED")
            .jsonPath("$.watch.closedAt").exists()
            .jsonPath("$.timeline[1].type").isEqualTo("DISARMED_BEFORE_DEPARTURE");
    }

    // ------------------------------------------------------------------ outils

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec armer(
            Compte owner, UUID scheduleId, UUID guardianId) {
        return webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange();
    }

    private UUID creerCreneau(Compte owner) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Instant startsAt = Instant.now().plus(2, ChronoUnit.HOURS);
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, startsAt, null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    /** Un contact externe créé puis accepté via la page publique de consentement. */
    private UUID contactAccepte(Compte owner) {
        UUID guardianId = contactNonAccepte(owner);
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private UUID contactNonAccepte(Compte owner) {
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "email", "proche@example.org"))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("watch");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Watch" + UUID.randomUUID().toString().substring(0, 8)))
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
