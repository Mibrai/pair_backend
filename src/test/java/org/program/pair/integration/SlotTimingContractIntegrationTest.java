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
 * P-BL-15 et P-BA-19 (décision du 13/09) — une séance a toujours une fin, à
 * l'écriture comme en base, et la fin effective se publie.
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

    /**
     * V120 : la base refuse elle-même une séance sans fin. Les anciens cas —
     * fin effective non déclarée, veille sans échéance devinable — ne peuvent
     * plus se produire ; leurs gardes restent dans le code, en défense.
     */
    @Test
    void laBase_refuseUnCreneauSansFin() {
        String token = compte();
        Map<?, ?> cree = publier(token, Instant.now().plus(2, ChronoUnit.DAYS));
        UUID scheduleId = UUID.fromString(String.valueOf(cree.get("scheduleId")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE schedules SET ends_at = NULL WHERE id = ?", scheduleId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
        adresseVerifiee(email);
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
