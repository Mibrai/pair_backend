package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * « Déjà pratiquée près de toi » — demande mobile du 14/09/2026 (modules/rappel,
 * P-MU-22 A1). Un booléen par activité, jamais un compte, à partir de trois
 * personnes visibles autres que l'appelant.
 */
class PractisedNearbyIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Clermont-Ferrand. La mer du Nord pour le « nulle part ».
    private static final double LAT = 45.7772;
    private static final double LNG = 3.0870;
    private static final double MER_LAT = 55.5;
    private static final double MER_LNG = 3.5;

    private final GeometryFactory geometrie = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void troisPersonnesVisibles_disentOui_deuxNon_etAucunCompteNestServi() throws Exception {
        Compte moi = inscrire();
        Activity courante = activite("courante");
        Activity rare = activite("rare");
        for (int i = 0; i < 3; i++) pratiquant(courante, LAT + i * 0.01, LNG, true);
        for (int i = 0; i < 2; i++) pratiquant(rare, LAT, LNG + i * 0.01, true);

        JsonNode reponse = lire(moi, LAT, LNG, rare.getId(), UUID.randomUUID(), courante.getId());

        assertThat(reponse).hasSize(2);
        assertThat(reponse.get(0).get("activityId").asText()).as("ordre reçu").isEqualTo(rare.getId().toString());
        assertThat(reponse.get(0).get("practisedNearby").asBoolean()).isFalse();
        assertThat(reponse.get(1).get("activityId").asText()).isEqualTo(courante.getId().toString());
        assertThat(reponse.get(1).get("practisedNearby").asBoolean()).isTrue();
        reponse.forEach(entree -> entree.fieldNames().forEachRemaining(champ ->
            assertThat(champ).as("aucun autre champ").isIn("activityId", "practisedNearby")));

        assertThat(lire(moi, MER_LAT, MER_LNG, courante.getId()).get(0).get("practisedNearby").asBoolean())
            .as("au milieu de la mer du Nord").isFalse();
    }

    @Test
    void lAppelant_unePersonneCachee_etUnePersonneBloquee_neComptentPas() throws Exception {
        Compte moi = inscrire();
        Activity activite = activite("sans-soi");
        declarer(userRepository.findById(moi.id()).orElseThrow(), activite, LAT, LNG, true);
        pratiquant(activite, LAT, LNG, true);
        pratiquant(activite, LAT, LNG, false);
        User bloquee = pratiquant(activite, LAT, LNG, true);
        jdbcTemplate.update("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
            bloquee.getId(), moi.id());

        assertThat(lire(moi, LAT, LNG, activite.getId()).get(0).get("practisedNearby").asBoolean()).isFalse();

        pratiquant(activite, LAT, LNG, true);
        pratiquant(activite, LAT, LNG, true);
        assertThat(lire(moi, LAT, LNG, activite.getId()).get(0).get("practisedNearby").asBoolean()).isTrue();
    }

    @Test
    void lesRefus() {
        Compte moi = inscrire();
        UUID id = activite("refus").getId();

        webTestClient.get().uri("/api/activities/practised-nearby?lat={a}&lng={b}&activityIds={c}", LAT, LNG, id)
            .exchange().expectStatus().isUnauthorized();

        for (String requete : List.of(
                "lat=91&lng=3&activityIds=" + id,
                "lat=45&lng=181&activityIds=" + id,
                "lng=3&activityIds=" + id,
                "lat=45&lng=3&activityIds=",
                "lat=45&lng=3",
                "lat=45&lng=3&activityIds=pas-un-uuid",
                "lat=45&lng=3&activityIds=" + IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID().toString())
                    .collect(Collectors.joining(",")))) {
            webTestClient.get().uri("/api/activities/practised-nearby?" + requete)
                .headers(h -> h.setBearerAuth(moi.token()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }

        webTestClient.get().uri("/api/activities/practised-nearby?lat={a}&lng={b}&activityIds={c}",
                LAT, LNG, UUID.randomUUID())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().json("[]");
    }

    @Test
    void auDelaDuPlafond_uneLectureRend429AvecRetryAfter() {
        Compte moi = inscrire();
        UUID id = activite("plafond").getId();
        // lesRefus a pu consommer du budget : le limiteur est remis à zéro avant chaque test.
        for (int i = 0; i < 30; i++) {
            webTestClient.get().uri("/api/activities/practised-nearby?lat={a}&lng={b}&activityIds={c}", LAT, LNG, id)
                .headers(h -> h.setBearerAuth(moi.token()))
                .exchange().expectStatus().isOk();
        }
        webTestClient.get().uri("/api/activities/practised-nearby?lat={a}&lng={b}&activityIds={c}", LAT, LNG, id)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(429)
            .expectHeader().exists("Retry-After");
    }

    @Test
    void laRouteEstAuContrat_sansAucunChampEntier() throws Exception {
        String corps = webTestClient.get().uri("/v3/api-docs")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        JsonNode doc = objectMapper.readTree(corps);

        assertThat(doc.at("/paths/~1api~1activities~1practised-nearby/get").isMissingNode()).isFalse();
        JsonNode proprietes = doc.at("/components/schemas/PractisedNearbyDto/properties");
        List<String> types = new ArrayList<>();
        proprietes.forEach(p -> types.add(p.path("type").asText()));
        assertThat(types).doesNotContain("integer", "number");
        assertThat(proprietes.at("/practisedNearby/description").asText()).contains("3 personnes");
    }

    // — décor —

    private record Compte(UUID id, String token) {}

    private Compte inscrire() {
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail("pratique-proche"), "Password123!", "Proche"))
            .exchange().expectStatus().isCreated()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Compte(auth.userId(), auth.accessToken());
    }

    private JsonNode lire(Compte compte, double lat, double lng, UUID... ids) throws Exception {
        String liste = java.util.Arrays.stream(ids).map(UUID::toString).collect(Collectors.joining(","));
        String corps = webTestClient.get()
            .uri("/api/activities/practised-nearby?lat={a}&lng={b}&activityIds={c}", lat, lng, liste)
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        return objectMapper.readTree(corps);
    }

    private Activity activite(String suffixe) {
        String nom = "pratique-proche-" + suffixe + "-" + UUID.randomUUID().toString().substring(0, 8);
        return activityRepository.save(Activity.builder()
            .name(nom).slug(nom).description("fixture").icon("sports")
            .category(activityRepository.findAll().get(0).getCategory())
            .build());
    }

    private User pratiquant(Activity activite, double lat, double lng, boolean positionPublique) {
        User personne = userRepository.save(User.builder()
            .email(uniqueEmail("pratiquant"))
            .passwordHash("$2a$10$notusedbecausethisuserneverlogsin000000000000000000000")
            .displayName("Pratiquant")
            .isActive(true)
            .build());
        declarer(personne, activite, lat, lng, positionPublique);
        return personne;
    }

    private void declarer(User personne, Activity activite, double lat, double lng, boolean positionPublique) {
        personne.setLocation(geometrie.createPoint(new Coordinate(lng, lat)));
        personne.setLocationPublic(positionPublique);
        userRepository.save(personne);
        userActivityRepository.save(UserActivity.builder()
            .user(personne).activity(activite).visibleOnMap(true).build());
    }
}
