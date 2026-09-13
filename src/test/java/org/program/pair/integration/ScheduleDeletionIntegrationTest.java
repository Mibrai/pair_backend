package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-16 — le {@code DELETE} d'un créneau vérifie son programme, annule par le
 * chemin commun, et dit ce qu'il a fait.
 *
 * <p>Décor à Rennes : la base est partagée avec le reste de la suite.
 */
class ScheduleDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.1113;
    private static final double LNG = -1.6800;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void supprimerUnCreneauParLAdresseDUnAutreProgramme_rend404_etNeToucheARien() {
        String hote = compte();
        Creneau cible = publier(hote);
        Creneau autre = publier(hote);

        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}", autre.programId(), cible.scheduleId())
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isNotFound();

        assertThat(statut(cible.scheduleId())).isEqualTo("OPEN");
    }

    /** Étape 2 : le même 404 qu'un créneau inexistant, rien ne dit qu'il existe. */
    @Test
    void unNonProprietaire_recoitLeMeme404_etNeToucheARien() {
        Creneau creneau = publier(compte());

        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}", creneau.programId(), creneau.scheduleId())
            .headers(h -> h.setBearerAuth(compte()))
            .exchange().expectStatus().isNotFound()
            .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");

        assertThat(statut(creneau.scheduleId())).isEqualTo("OPEN");
    }

    @Test
    void supprimerUnCreneauSansInscrit_leRetire_etRepondDeleted() {
        String hote = compte();
        Creneau creneau = publier(hote);
        // Le créneau rapide inscrit l'organisateur au fil du programme, pas au
        // créneau : personne d'autre n'est concerné.

        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}", creneau.programId(), creneau.scheduleId())
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.outcome").isEqualTo("DELETED")
            .jsonPath("$.schedule").doesNotExist();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedules WHERE id = ?",
            Long.class, creneau.scheduleId())).isZero();
    }

    @Test
    void supprimerUnCreneauAvecInscrits_lAnnuleParLeCheminCommun_etRepondCancelled() {
        String hote = compte();
        Creneau creneau = publier(hote);
        String inscrit = compte();
        webTestClient.post().uri("/api/slots/{id}/join", creneau.scheduleId())
            .headers(h -> h.setBearerAuth(inscrit))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();

        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}", creneau.programId(), creneau.scheduleId())
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.outcome").isEqualTo("CANCELLED")
            .jsonPath("$.schedule.id").isEqualTo(creneau.scheduleId().toString())
            .jsonPath("$.schedule.status").isEqualTo("CANCELLED");

        // Le chemin commun pose l'auteur et la date, ce que l'ancien chemin ne faisait pas.
        Map<String, Object> ligne = jdbcTemplate.queryForMap(
            "SELECT status, cancelled_at, cancelled_by FROM schedules WHERE id = ?", creneau.scheduleId());
        assertThat(ligne.get("status")).isEqualTo("CANCELLED");
        assertThat(ligne.get("cancelled_at")).isNotNull();
        assertThat(ligne.get("cancelled_by")).isNotNull();
        assertThat(annulationsRecues(inscrit)).as("une seule notification d'annulation").isEqualTo(1);
    }

    // — décor —

    private record Creneau(UUID scheduleId, UUID programId) {}

    private Creneau publier(String token) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> corps = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), (Instant.now().plus(3, ChronoUnit.DAYS)).plus(java.time.Duration.ofHours(2)),
                "Parc du Thabor", PlaceType.PUBLIC, LAT, LNG,
                "Place Saint-Mélaine, Rennes", null, "Rennes", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(corps).isNotNull();
        return new Creneau(UUID.fromString(String.valueOf(corps.get("scheduleId"))),
            UUID.fromString(String.valueOf(corps.get("programId"))));
    }

    private String statut(UUID scheduleId) {
        return jdbcTemplate.queryForObject("SELECT status FROM schedules WHERE id = ?", String.class, scheduleId);
    }

    /** {@code notify} est asynchrone : on attend que la ligne arrive, puis on vérifie qu'elle est seule. */
    private long annulationsRecues(String token) {
        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        long compte = 0;
        for (int essai = 0; essai < 50 && compte == 0; essai++) {
            compte = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'SLOT_CANCELLED'", Long.class, id);
            if (compte == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        try {
            Thread.sleep(300); // laisser passer une éventuelle seconde notification
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'SLOT_CANCELLED'", Long.class, id);
    }

    private String compte() {
        String email = uniqueEmail("suppression");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
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
