package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
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
 * Lot A2 — le chemin court « je cherche quelqu'un pour… ».
 *
 * <p>Trois de ces cas viennent d'un signalement du terrain plutôt que de la
 * spécification : l'adresse réputée facultative mais exigée à l'exécution, les
 * coordonnées revenues à 0,0, et le créneau en ligne impossible à créer. Ils
 * sont vérifiés ici sur la nouvelle route <b>avant</b> qu'elle ne soit déclarée
 * prête.
 */
class QuickSlotIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void unCreneauRapide_doitEtreCreeEnUnSeulAppel() {
        String token = registerAndLogin();

        SlotFeedItemDto slot = create(token, request(PlaceType.PUBLIC, LAT, LNG));

        assertThat(slot.scheduleId()).isNotNull();
        assertThat(slot.programId()).isNotNull();
        assertThat(slot.isOpenToPartners()).isTrue();
        // Le titre est fabriqué par le serveur : personne ne l'a écrit.
        assertThat(slot.programTitle()).isNotBlank().contains("—");
    }

    @Test
    void leCreneauRapide_doitApparaitreDansLeFil() {
        // Le piège du lot : createProgram naît en DRAFT, et le fil exige ACTIVE.
        // Un créneau créé en brouillon rendrait 201 et resterait invisible — un
        // test qui ne vérifie que le code de retour ne le verrait jamais.
        String token = registerAndLogin();
        SlotFeedItemDto created = create(token, request(PlaceType.PUBLIC, LAT, LNG));

        String otherToken = registerAndLogin();
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000).build())
            .headers(h -> h.setBearerAuth(otherToken))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class)
            .returnResult().getResponseBody();

        assertThat(feed).extracting(SlotFeedItemDto::scheduleId).contains(created.scheduleId());
    }

    @Test
    void lesCoordonnees_doiventEtrePersisteesTellesQuellesEnBase() {
        // Le terrain signalait des créneaux revenus à 0,0. On relit la géométrie
        // en base plutôt que le DTO : c'est la seule façon de distinguer une
        // écriture correcte d'un affichage correct.
        String token = registerAndLogin();
        SlotFeedItemDto slot = create(token, request(PlaceType.PUBLIC, LAT, LNG));

        Map<String, Object> stored = jdbcTemplate.queryForMap(
            "SELECT ST_X(location) AS lng, ST_Y(location) AS lat FROM schedules WHERE id = ?",
            slot.scheduleId());

        assertThat((Double) stored.get("lat")).isCloseTo(LAT, org.assertj.core.data.Offset.offset(1e-6));
        assertThat((Double) stored.get("lng")).isCloseTo(LNG, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void unLieuPublicSansAdresse_doitEtreRefuseProprement() {
        // L'adresse est décrite comme facultative par le contrat, et exigée à
        // l'exécution dès que le lieu est public. C'est vrai, et c'est un 400
        // lisible — pas une erreur serveur.
        String token = registerAndLogin();

        QuickSlotRequest request = new QuickSlotRequest(
            anyActivityId(), Instant.now().plus(2, ChronoUnit.DAYS), null,
            "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
            null, null, null, null, null, null, null);

        webTestClient.post()
            .uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void unCreneauEnLigne_doitEtreCreeSansCoordonnees() {
        // Avant V61, schedules.location était NOT NULL alors que le code n'en
        // pose aucune pour un lieu ONLINE : la création partait en violation de
        // contrainte, et envoyer 0,0 était le contournement naturel.
        String token = registerAndLogin();

        SlotFeedItemDto slot = create(token, request(PlaceType.ONLINE, null, null));

        assertThat(slot.scheduleId()).isNotNull();
        assertThat(slot.lat()).isNull();
        assertThat(slot.lng()).isNull();

        Long withLocation = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schedules WHERE id = ? AND location IS NOT NULL",
            Long.class, slot.scheduleId());
        assertThat(withLocation).isZero();
    }

    @Test
    void unLieuPhysiqueSansCoordonnees_doitEtreRefuseProprement() {
        String token = registerAndLogin();

        webTestClient.post()
            .uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request(PlaceType.PUBLIC, null, null))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void deuxCreneauxRapides_surLaMemeActivite_doiventPasser() {
        // addActivityToProfile lève un 409 quand l'activité est déjà déclarée.
        // Le chemin court ne doit pas s'appuyer dessus, sans quoi le deuxième
        // créneau d'une même personne échouerait — c'est-à-dire le cas normal.
        String token = registerAndLogin();
        UUID activityId = anyActivityId();

        create(token, request(activityId, PlaceType.PUBLIC, LAT, LNG, 2));
        SlotFeedItemDto second = create(token, request(activityId, PlaceType.PUBLIC, LAT, LNG, 9));

        assertThat(second.scheduleId()).isNotNull();
    }

    @Test
    void lActivite_doitEtreDeclareeAuProfil() {
        // « Chercher quelqu'un pour une activité, c'est la pratiquer. »
        String token = registerAndLogin();
        UUID activityId = anyActivityId();

        create(token, request(activityId, PlaceType.PUBLIC, LAT, LNG, 2));

        webTestClient.get()
            .uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[?(@.activity.id == '" + activityId + "')]").exists();
    }

    @Test
    void leProgramme_doitPorterSonModeDeCreation() {
        String token = registerAndLogin();
        SlotFeedItemDto slot = create(token, request(PlaceType.PUBLIC, LAT, LNG));

        webTestClient.get()
            .uri("/api/programs/{id}", slot.programId())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.createdVia").isEqualTo("QUICK");
    }

    // — helpers —

    private SlotFeedItemDto create(String token, QuickSlotRequest request) {
        return webTestClient.post()
            .uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class)
            .returnResult().getResponseBody();
    }

    private QuickSlotRequest request(PlaceType placeType, Double lat, Double lng) {
        return request(anyActivityId(), placeType, lat, lng, 2);
    }

    private QuickSlotRequest request(UUID activityId, PlaceType placeType,
                                     Double lat, Double lng, int inDays) {
        return new QuickSlotRequest(
            activityId,
            Instant.now().plus(inDays, ChronoUnit.DAYS),
            null,
            placeType == PlaceType.ONLINE ? "Visioconférence" : "Parc de l'Orangerie",
            placeType,
            lat,
            lng,
            placeType == PlaceType.PUBLIC ? "1 avenue de l'Europe" : null,
            null,
            "Strasbourg",
            null,
            "Venez comme vous êtes",
            null,
            null);
    }

    private UUID anyActivityId() {
        Activity activity = activityRepository.findAll().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Aucune activité en base : les migrations de semis n'ont pas tourné."));
        return activity.getId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("quickslot");
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse auth = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
