package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BL-15 et P-BA-19 (décision du 13/09) — une séance a toujours une fin, la
 * fin effective se publie, et la veille ne s'arme pas sur une durée inventée.
 *
 * <p>Décor à Nantes : la base est partagée avec le reste de la suite.
 */
class SlotTimingContractIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 47.2184;
    private static final double LNG = -1.5536;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void publierUnCreneauSansFin_doitEtreRefuse() {
        String token = compte();
        Map<String, Object> corps = creneauRapide(Instant.now().plus(2, ChronoUnit.DAYS));
        corps.remove("endsAt");

        webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void unCreneau_publieSaFinEffective_etDitQuElleEstDeclaree() {
        String token = compte();
        Instant debut = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<?, ?> cree = publier(token, debut);
        UUID programId = UUID.fromString(String.valueOf(cree.get("programId")));

        webTestClient.get().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.schedules[0].endsAtDeclared").isEqualTo(true)
            .jsonPath("$.schedules[0].effectiveEndsAt").isEqualTo(debut.plus(90, ChronoUnit.MINUTES).toString());
    }

    @Test
    void unAncienCreneauSansFin_annonceUneFinEffective_etDitQuElleNEstPasDeclaree() {
        String token = compte();
        Instant debut = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<?, ?> cree = publier(token, debut);
        UUID scheduleId = UUID.fromString(String.valueOf(cree.get("scheduleId")));
        UUID programId = UUID.fromString(String.valueOf(cree.get("programId")));
        jdbcTemplate.update("UPDATE schedules SET ends_at = NULL WHERE id = ?", scheduleId);

        webTestClient.get().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.schedules[0].endsAt").doesNotExist()
            .jsonPath("$.schedules[0].endsAtDeclared").isEqualTo(false)
            .jsonPath("$.schedules[0].effectiveEndsAt").isEqualTo(debut.plus(2, ChronoUnit.HOURS).toString());
    }

    @Test
    void armerUneVeilleSansHeureLimite_surUnCreneauSansFin_doitEtreRefuse() {
        String token = compte();
        Map<?, ?> cree = publier(token, Instant.now().plus(1, ChronoUnit.HOURS));
        UUID scheduleId = UUID.fromString(String.valueOf(cree.get("scheduleId")));
        jdbcTemplate.update("UPDATE schedules SET ends_at = NULL WHERE id = ?", scheduleId);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_DEADLINE_REQUIRED");

        // Avec une heure limite demandée, elle s'arme : c'est l'échéance devinée
        // qui est refusée, pas la veille.
        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                "deadlineAt", Instant.now().plus(5, ChronoUnit.HOURS).toString()))
            .exchange().expectStatus().isCreated();
    }

    private Map<?, ?> publier(String token, Instant debut) {
        Map<?, ?> corps = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(creneauRapide(debut))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(corps).isNotNull();
        return corps;
    }

    private Map<String, Object> creneauRapide(Instant debut) {
        Map<String, Object> corps = new HashMap<>();
        corps.put("activityId", activityRepository.findAll().get(0).getId().toString());
        corps.put("startsAt", debut.toString());
        corps.put("endsAt", debut.plus(90, ChronoUnit.MINUTES).toString());
        corps.put("placeName", "Jardin des Plantes");
        corps.put("placeType", "PUBLIC");
        corps.put("lat", LAT);
        corps.put("lng", LNG);
        corps.put("addressPublic", "Boulevard de Stalingrad, Nantes");
        corps.put("city", "Nantes");
        return corps;
    }

    private String compte() {
        String email = uniqueEmail("fin-creneau");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Fin"))
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
