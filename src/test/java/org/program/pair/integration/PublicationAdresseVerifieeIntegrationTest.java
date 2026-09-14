package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.dto.ErrorResponse;
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
 * P-MU-17, demande mobile du 14/09/2026 (modules/verification-email) — un compte
 * dont l'adresse n'est pas vérifiée ne publie pas de créneau, et le refus porte
 * un code que l'app traduit.
 *
 * <p>Ce qui reste permis est vérifié autant que ce qui est refusé : l'app
 * alignera son parcours de brouillon sur cette liste.
 */
class PublicationAdresseVerifieeIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Limoges, que personne d'autre n'emploie.
    private static final double LAT = 45.8336;
    private static final double LNG = 1.2611;

    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void unCreneauRapide_estRefuseSansAdresseVerifiee_etRienNestCree() {
        Compte compte = inscrire("publication-rapide");

        ErrorResponse refus = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(creneauRapide())
            .exchange().expectStatus().isForbidden()
            .expectBody(ErrorResponse.class).returnResult().getResponseBody();

        assertThat(refus).isNotNull();
        assertThat(refus.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_activities WHERE user_id = ?", Long.class, compte.id()))
            .as("ni activité déclarée, ni programme, ni créneau").isZero();

        adresseVerifiee(compte.email());

        webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(creneauRapide())
            .exchange().expectStatus().isCreated();
    }

    @Test
    void unBrouillon_etSesCreneaux_restentPermis_maisSonActivationEstRefusee() {
        Compte compte = inscrire("publication-brouillon");
        UUID programId = brouillon(compte);

        // Un créneau dans un brouillon : personne ne le voit, il est accepté.
        webTestClient.post().uri("/api/programs/{id}/schedules", programId)
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(creneau())
            .exchange().expectStatus().isCreated();

        for (String methode : new String[] {"PATCH", "PUT"}) {
            var requete = methode.equals("PATCH")
                ? webTestClient.patch().uri("/api/programs/{id}", programId)
                : webTestClient.put().uri("/api/programs/{id}", programId);
            ErrorResponse refus = requete
                .headers(h -> h.setBearerAuth(compte.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "ACTIVE"))
                .exchange().expectStatus().isForbidden()
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
            assertThat(refus.code()).as(methode).isEqualTo("EMAIL_NOT_VERIFIED");
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM programs WHERE id = ?", String.class, programId)).isEqualTo("DRAFT");

        adresseVerifiee(compte.email());

        webTestClient.patch().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "ACTIVE"))
            .exchange().expectStatus().isOk();
    }

    @Test
    void unCreneauPoseHorsBrouillon_estRefuse() {
        Compte compte = inscrire("publication-actif");
        UUID programId = brouillon(compte);
        // Un programme déjà actif d'un compte non vérifié : l'état qu'ont laissé
        // les publications d'avant le contrôle.
        jdbcTemplate.update("UPDATE programs SET status = 'ACTIVE' WHERE id = ?", programId);

        ErrorResponse refus = webTestClient.post().uri("/api/programs/{id}/schedules", programId)
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(creneau())
            .exchange().expectStatus().isForbidden()
            .expectBody(ErrorResponse.class).returnResult().getResponseBody();

        assertThat(refus.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schedules WHERE program_id = ?", Long.class, programId)).isZero();
    }

    @Test
    void leRefusEstAuContrat() throws Exception {
        String corps = webTestClient.get().uri("/v3/api-docs")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        JsonNode chemins = objectMapper.readTree(corps).get("paths");

        assertThat(chemins.at("/~1api~1quick-slots/post/responses/403/description").asText())
            .contains("EMAIL_NOT_VERIFIED");
        assertThat(chemins.at("/~1api~1programs~1{programId}~1schedules/post/responses/403/description").asText())
            .contains("EMAIL_NOT_VERIFIED");
        assertThat(chemins.at("/~1api~1programs~1{programId}/patch/responses/403/description").asText())
            .contains("EMAIL_NOT_VERIFIED");
    }

    // — décor —

    private record Compte(UUID id, String email, String token) {}

    private Compte inscrire(String prefixe) {
        String email = uniqueEmail(prefixe);
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Publication"))
            .exchange().expectStatus().isCreated()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth.verificationStatus()).isEqualTo("UNVERIFIED");
        return new Compte(auth.userId(), email, auth.accessToken());
    }

    private UUID brouillon(Compte compte) {
        Activity activite = activityRepository.findAll().stream().findFirst().orElseThrow();
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(userRepository.findById(compte.id()).orElseThrow()).activity(activite).build());

        ProgramDto dto = webTestClient.post().uri("/api/programs")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userActivityId", ua.getId(), "title", "Brouillon à vérifier"))
            .exchange().expectStatus().isCreated()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();
        return dto.id();
    }

    private Map<String, Object> creneau() {
        Instant debut = Instant.now().plus(3, ChronoUnit.DAYS);
        Map<String, Object> corps = new HashMap<>();
        corps.put("placeName", "Jardin de l'Évêché");
        corps.put("placeType", "PUBLIC");
        corps.put("lat", LAT);
        corps.put("lng", LNG);
        corps.put("addressPublic", "Place de l'Évêché, Limoges");
        corps.put("startsAt", debut.toString());
        corps.put("endsAt", debut.plus(1, ChronoUnit.HOURS).toString());
        return corps;
    }

    private QuickSlotRequest creneauRapide() {
        Instant debut = Instant.now().plus(2, ChronoUnit.DAYS);
        return new QuickSlotRequest(
            activityRepository.findAll().stream().findFirst().orElseThrow().getId(),
            debut, debut.plus(1, ChronoUnit.HOURS),
            "Jardin de l'Évêché", PlaceType.PUBLIC, LAT, LNG,
            "Place de l'Évêché, Limoges", null, null, null, null, null, null);
    }
}
