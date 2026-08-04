package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.map.dto.MapActivitiesResponse;
import org.program.pair.domain.map.dto.MapActivityMarkerDto;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande 5 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md : GET
 * /api/map/activities accepte un bornage géographique et l'applique réellement,
 * respecte un limit explicite, et signale la troncature au lieu de renvoyer
 * silencieusement une carte partielle.
 *
 * <p>Les fixtures sont posées au milieu de l'Atlantique (10°N, 30°O), loin de
 * toute donnée de seed : les assertions portent donc sur des ensembles exacts et
 * non sur des « au moins un », malgré une base peuplée par les migrations Flyway.
 *
 * <p>Un seul compte pour toute la classe (garde statique) : l'inscription est
 * limitée à 5/heure/IP et le budget est partagé entre classes de test.
 */
class MapActivitiesBoundingIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /** Point de référence, volontairement hors de toute zone peuplée par les seeds. */
    private static final double ORIGIN_LAT = 10.0;
    private static final double ORIGIN_LNG = -30.0;
    /** ~60 km au nord de l'origine (1° de latitude ≈ 111,32 km). */
    private static final double FAR_LAT = 10.539;
    private static final double FAR_LNG = -30.0;

    private static final String HOST_EMAIL = "map-bounding-host@pair.app";

    private static boolean fixturesCreated = false;
    private static String token;

    @BeforeEach
    void setUp() {
        if (!fixturesCreated) {
            token = registerAndLogin(HOST_EMAIL);
            User host = userRepository.findByEmail(HOST_EMAIL).orElseThrow();
            createLocatedSchedule(host, "Carte bornage — proche", ORIGIN_LAT, ORIGIN_LNG);
            createLocatedSchedule(host, "Carte bornage — lointain", FAR_LAT, FAR_LNG);
            fixturesCreated = true;
        }
    }

    @Test
    void sansAucunParametre_laReponseResteCelleDavant() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities").build());

        assertThat(response.truncated()).isFalse();
        assertThat(response.totalInBounds()).isEqualTo(response.activities().size());
        assertThat(response.defaultCenter()).isNotNull();
        // Aucun filtre : les deux fixtures sont là, au milieu des données de seed.
        assertThat(atlanticMarkers(response)).hasSize(2);
        for (MapActivityMarkerDto marker : response.activities()) {
            assertThat(marker.distanceKm()).as("pas de position utilisateur, pas de distance").isNull();
        }
    }

    @Test
    void radiusMeters_doitEcarterUneActiviteHorsRayon() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities")
            .queryParam("userLat", ORIGIN_LAT)
            .queryParam("userLng", ORIGIN_LNG)
            .queryParam("radiusMeters", 25_000)
            .build());

        // Critère du prompt client : une activité à 60 km n'apparaît pas pour
        // radiusMeters=25000.
        assertThat(response.activities()).extracting(MapActivityMarkerDto::lat)
            .contains(ORIGIN_LAT)
            .doesNotContain(FAR_LAT);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void radiusMeters_assezLarge_doitRamenerLesDeux() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities")
            .queryParam("userLat", ORIGIN_LAT)
            .queryParam("userLng", ORIGIN_LNG)
            .queryParam("radiusMeters", 100_000)
            .build());

        assertThat(atlanticMarkers(response)).hasSize(2);
        assertThat(response.totalInBounds()).isEqualTo(2);
    }

    @Test
    void bbox_doitEtreAppliquee() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities")
            .queryParam("south", ORIGIN_LAT - 0.1)
            .queryParam("north", ORIGIN_LAT + 0.1)
            .queryParam("west", ORIGIN_LNG - 0.1)
            .queryParam("east", ORIGIN_LNG + 0.1)
            .build());

        assertThat(response.activities()).extracting(MapActivityMarkerDto::lat)
            .containsExactly(ORIGIN_LAT);
        assertThat(response.totalInBounds()).isEqualTo(1);
    }

    @Test
    void limit_doitEtreRespecte_etLaTroncatureSignalee() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities")
            .queryParam("userLat", ORIGIN_LAT)
            .queryParam("userLng", ORIGIN_LNG)
            .queryParam("radiusMeters", 100_000)
            .queryParam("limit", 1)
            .build());

        assertThat(response.activities()).hasSize(1);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalInBounds())
            .as("totalInBounds compte la zone, pas la page")
            .isEqualTo(2);
        assertThat(response.activities().get(0).lat())
            .as("la troncature garde le plus proche, pas un marqueur arbitraire")
            .isEqualTo(ORIGIN_LAT);
    }

    @Test
    void deuxAppelsIdentiques_doiventRenvoyerLeMemeOrdre() {
        Function<UriBuilder, java.net.URI> uri = b -> b.path("/api/map/activities")
            .queryParam("userLat", ORIGIN_LAT)
            .queryParam("userLng", ORIGIN_LNG)
            .queryParam("radiusMeters", 100_000)
            .build();

        assertThat(fetch(uri).activities()).extracting(MapActivityMarkerDto::lat)
            .isEqualTo(fetch(uri).activities().stream().map(MapActivityMarkerDto::lat).toList());
    }

    @Test
    void radiusSansPositionUtilisateur_doitEtreRefuse() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("radiusMeters", 25_000)
            .build(), "MAP_RADIUS_REQUIRES_USER_LOCATION");
    }

    @Test
    void radiusHorsBornes_doitEtreRefuse() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("userLat", ORIGIN_LAT)
            .queryParam("userLng", ORIGIN_LNG)
            .queryParam("radiusMeters", 500_000)
            .build(), "MAP_RADIUS_OUT_OF_RANGE");
    }

    @Test
    void bboxIncomplete_doitEtreRefusee() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("south", ORIGIN_LAT - 0.1)
            .queryParam("north", ORIGIN_LAT + 0.1)
            .build(), "MAP_BOUNDS_INCOMPLETE");
    }

    @Test
    void bboxInversee_doitEtreRefusee() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("south", ORIGIN_LAT + 0.1)
            .queryParam("north", ORIGIN_LAT - 0.1)
            .queryParam("west", ORIGIN_LNG - 0.1)
            .queryParam("east", ORIGIN_LNG + 0.1)
            .build(), "MAP_BOUNDS_INVALID");
    }

    @Test
    void limitNul_doitEtreRefuse() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("limit", 0)
            .build(), "MAP_LIMIT_OUT_OF_RANGE");
    }

    // — helpers —

    /** Marqueurs des fixtures atlantiques, isolés des données de seed. */
    private List<MapActivityMarkerDto> atlanticMarkers(MapActivitiesResponse response) {
        return response.activities().stream()
            .filter(m -> m.lng() < -25.0 && m.lng() > -35.0)
            .toList();
    }

    private MapActivitiesResponse fetch(Function<UriBuilder, java.net.URI> uri) {
        return webTestClient.get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private void expectError(Function<UriBuilder, java.net.URI> uri, String expectedCode) {
        webTestClient.get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo(expectedCode);
    }

    private void createLocatedSchedule(User owner, String title, double lat, double lng) {
        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository
            .findByUserIdAndActivityId(owner.getId(), activity.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(owner).activity(activity).build()));

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName(title)
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Bornage")
            .showExactAddress(true)
            .location(geometryFactory.createPoint(new Coordinate(lng, lat)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
            .maxParticipants(8)
            .isOpenToPartners(true)
            .status(SlotStatus.OPEN)
            .build());
    }

    private String registerAndLogin(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
