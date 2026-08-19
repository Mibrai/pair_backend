package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D1 — langues parlées.
 *
 * <p>Le point de conception qui se vérifie ici : <b>un créneau qui ne déclare
 * aucune langue n'est jamais exclu</b>. La plupart n'en déclareront pas, et
 * exclure faute d'information punirait ceux qui n'ont rien rempli.
 */
class UserLanguageIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — déclarer —

    @Test
    void laListe_doitEtreRemplacee_enUnSeulAppel() {
        String token = registerAndLogin();

        assertThat(replace(token, List.of(
            Map.of("language", "fr", "proficiency", "NATIVE"),
            Map.of("language", "en", "proficiency", "CONVERSATIONAL")))).hasSize(2);

        // Remplacement, pas fusion : le client envoie ce qu'il veut voir.
        List<Map> after = replace(token, List.of(
            Map.of("language", "de", "proficiency", "BASIC")));
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("language")).isEqualTo("de");
    }

    @Test
    void lesEtiquettes_doiventEtreNormalisees() {
        // « FR » et « fr » sont la même langue : sans normalisation, la clé
        // primaire composite refuserait la seconde par une violation
        // d'intégrité plutôt que par un message lisible.
        String token = registerAndLogin();

        List<Map> languages = replace(token, List.of(
            Map.of("language", "FR", "proficiency", "NATIVE"),
            Map.of("language", "fr", "proficiency", "BASIC")));

        assertThat(languages).hasSize(1);
        assertThat(languages.get(0).get("language")).isEqualTo("fr");
    }

    @Test
    void uneEtiquetteInvalide_doitEtreRefusee() {
        String token = registerAndLogin();

        webTestClient.put().uri("/api/users/me/languages")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(List.of(Map.of("language", "123", "proficiency", "NATIVE")))
            .exchange().expectStatus().isBadRequest();
    }

    // — filtrer le fil —

    @Test
    void unCreneauSansLangue_neDoitJamaisEtreExclu() {
        // Le cœur du lot. Un créneau qui n'annonce rien reste visible de tous.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String viewer = registerAndLogin();

        assertThat(feedIds(viewer, "de")).contains(slotId);
    }

    @Test
    void unCreneauDansUneAutreLangue_doitEtreEcarte() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        jdbcTemplate.update("UPDATE schedules SET primary_language = 'it' WHERE id = ?", slotId);

        String viewer = registerAndLogin();

        assertThat(feedIds(viewer, "de")).doesNotContain(slotId);
        assertThat(feedIds(viewer, "it")).contains(slotId);
    }

    @Test
    void sansFiltre_toutRemonte() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        jdbcTemplate.update("UPDATE schedules SET primary_language = 'it' WHERE id = ?", slotId);

        assertThat(feedIds(registerAndLogin(), null)).contains(slotId);
    }

    @Test
    void laLangue_doitApparaitreSurLeCreneau() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        jdbcTemplate.update("UPDATE schedules SET primary_language = 'it' WHERE id = ?", slotId);

        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();

        assertThat(slot.primaryLanguage()).isEqualTo("it");
    }

    // — filtrer la carte —

    @Test
    void laCarte_doitPouvoirNeGarderQueCeuxQuiParlentLaLangue() {
        String speaker = registerAndLogin();
        placeOnMap(speaker);
        replace(speaker, List.of(Map.of("language", "de", "proficiency", "FLUENT")));

        String other = registerAndLogin();
        placeOnMap(other);

        List<Map> markers = webTestClient.get()
            .uri(b -> b.path("/api/map/users")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000)
                .queryParam("languages", "de").build())
            .headers(h -> h.setBearerAuth(registerAndLogin()))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(markers).isNotEmpty();
        assertThat(markers).allSatisfy(marker ->
            assertThat(marker.get("userId")).isNotEqualTo(userId(other).toString()));
    }

    // — helpers —

    @SuppressWarnings("unchecked")
    private List<Map> replace(String token, List<Map<String, String>> languages) {
        return webTestClient.put().uri("/api/users/me/languages")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(languages)
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();
    }

    private List<UUID> feedIds(String token, String language) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> {
                var builder = b.path("/api/slots/feed")
                    .queryParam("lat", LAT).queryParam("lng", LNG)
                    .queryParam("radiusMeters", 20000);
                if (language != null) {
                    builder = builder.queryParam("languages", language);
                }
                return builder.build();
            })
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    /**
     * Pose une position ET la rend publique.
     *
     * <p>{@code locationPublic} vaut {@code false} par défaut, et les requêtes
     * de carte l'exigent : sans ce second appel, la personne a une position que
     * personne ne voit — et un test de filtrage passerait sur une liste vide.
     */
    private void placeOnMap(String token) {
        webTestClient.post().uri("/api/map/location")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("latitude", LAT, "longitude", LNG))
            .exchange().expectStatus().is2xxSuccessful();

        webTestClient.put().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("locationPublic", true))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID publishSlot(String token) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("lang");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Polyglotte"))
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
