package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
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
 * Demande mobile du 14/09/2026 (creneau-modifiable, TER) — le PUT d'un créneau
 * fusionne, et chaque champ a une valeur qui le retire. Chaque retrait est relu
 * par GET /api/programs/{id}, comme l'app le fera.
 */
class RetraitChampsCreneauIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Poitiers.
    private static final double LAT = 46.5802;
    private static final double LNG = 0.3404;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void uneChaineVide_retireLaRecurrence_etLaSeanceGardeSesInscrits() throws Exception {
        String hote = compte("retrait-serie");
        SlotFeedItemDto slot = creneau(hote, 4, "Venez comme vous êtes");
        modifier(hote, slot, Map.of("recurrenceRule", "FREQ=WEEKLY"));
        assertThat(seance(hote, slot).get("recurrenceRule").asText()).isEqualTo("FREQ=WEEKLY");

        String inscrit = compte("retrait-serie-inscrit");
        rejoindre(inscrit, slot.scheduleId());

        modifier(hote, slot, Map.of("recurrenceRule", ""));

        JsonNode relue = seance(hote, slot);
        assertThat(relue.path("recurrenceRule").isNull() || relue.path("recurrenceRule").isMissingNode()).isTrue();
        assertThat(relue.get("startsAt").asText()).as("la prochaine séance devient la seule")
            .isEqualTo(seanceInitiale(slot));
        assertThat(relue.get("participantCount").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT recurrence_rule IS NULL FROM schedules WHERE id = ?", Boolean.class, slot.scheduleId()))
            .as("null en base, jamais une chaîne vide").isTrue();
    }

    @Test
    void zero_retireLaLimite_etLaListeDAttenteEntre() throws Exception {
        String hote = compte("retrait-limite");
        SlotFeedItemDto slot = creneau(hote, 1, null);
        rejoindre(compte("retrait-limite-occupant"), slot.scheduleId());
        String candidat = compte("retrait-limite-candidat");
        webTestClient.post().uri("/api/slots/{id}/waitlist", slot.scheduleId())
            .headers(h -> h.setBearerAuth(candidat))
            .exchange().expectStatus().isCreated();

        modifier(hote, slot, Map.of("maxParticipants", 0));

        JsonNode relue = seance(hote, slot);
        assertThat(relue.path("maxParticipants").isNull() || relue.path("maxParticipants").isMissingNode()).isTrue();
        assertThat(relue.get("participantCount").asInt()).as("la file est entrée").isEqualTo(2);
        assertThat(relue.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void uneChaineVide_retireLeMotDAccueil_etLaVille() throws Exception {
        String hote = compte("retrait-mot");
        SlotFeedItemDto slot = creneau(hote, 4, "Rendez-vous devant la fontaine");

        modifier(hote, slot, Map.of("welcomeNote", "", "city", "  "));

        JsonNode relue = seance(hote, slot);
        assertThat(relue.path("welcomeNote").isNull() || relue.path("welcomeNote").isMissingNode()).isTrue();
        assertThat(jdbcTemplate.queryForMap(
            "SELECT welcome_note, city FROM schedules WHERE id = ?", slot.scheduleId()))
            .containsEntry("welcome_note", null).containsEntry("city", null);
    }

    @Test
    void nullOuAbsent_neTouchentARien_etEndsAtNeSeRetirePas() throws Exception {
        String hote = compte("retrait-rien");
        SlotFeedItemDto slot = creneau(hote, 4, "Mot gardé");
        String finAvant = seance(hote, slot).get("endsAt").asText();

        Map<String, Object> corps = new HashMap<>();
        corps.put("welcomeNote", null);
        corps.put("maxParticipants", null);
        corps.put("endsAt", null);
        modifier(hote, slot, corps);

        JsonNode relue = seance(hote, slot);
        assertThat(relue.get("welcomeNote").asText()).isEqualTo("Mot gardé");
        assertThat(relue.get("maxParticipants").asInt()).isEqualTo(4);
        assertThat(relue.get("endsAt").asText()).isEqualTo(finAvant);

        webTestClient.put().uri("/api/programs/{p}/schedules/{s}", slot.programId(), slot.scheduleId())
            .headers(h -> h.setBearerAuth(hote))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("maxParticipants", -1))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void laConventionEstAuContrat() throws Exception {
        String corps = webTestClient.get().uri("/v3/api-docs")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        JsonNode doc = objectMapper.readTree(corps);

        assertThat(doc.at("/paths/~1api~1programs~1{programId}~1schedules~1{scheduleId}/put/description").asText())
            .contains("recurrenceRule").contains("0 pour maxParticipants").contains("welcomeNote");
        JsonNode proprietes = doc.at("/components/schemas/UpdateScheduleRequest/properties");
        assertThat(proprietes.at("/recurrenceRule/description").asText()).contains("Chaîne vide");
        assertThat(proprietes.at("/maxParticipants/description").asText()).contains("0 : sans limite");
    }

    // — décor —

    private final Map<UUID, String> debuts = new HashMap<>();

    private String compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Retrait"))
            .exchange().expectStatus().isCreated()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        adresseVerifiee(email);
        return auth.accessToken();
    }

    private SlotFeedItemDto creneau(String hote, int places, String mot) {
        Instant debut = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(hote))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(activityRepository.findAll().get(0).getId(), debut,
                debut.plus(1, ChronoUnit.HOURS), "Parc de Blossac", PlaceType.PUBLIC, LAT, LNG,
                "Parc de Blossac, Poitiers", null, "Poitiers", places, mot, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        debuts.put(slot.scheduleId(), debut.toString());
        return slot;
    }

    private String seanceInitiale(SlotFeedItemDto slot) {
        return debuts.get(slot.scheduleId());
    }

    private void modifier(String hote, SlotFeedItemDto slot, Map<String, Object> corps) {
        webTestClient.put().uri("/api/programs/{p}/schedules/{s}", slot.programId(), slot.scheduleId())
            .headers(h -> h.setBearerAuth(hote))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(corps)
            .exchange().expectStatus().isOk();
    }

    private void rejoindre(String token, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    /** La séance telle que GET /api/programs/{id} la rend à l'organisateur. */
    private JsonNode seance(String hote, SlotFeedItemDto slot) throws Exception {
        String corps = webTestClient.get().uri("/api/programs/{id}", slot.programId())
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        for (JsonNode s : objectMapper.readTree(corps).get("schedules")) {
            if (s.get("id").asText().equals(slot.scheduleId().toString())) {
                return s;
            }
        }
        throw new AssertionError("séance absente de GET /api/programs/" + slot.programId());
    }
}
