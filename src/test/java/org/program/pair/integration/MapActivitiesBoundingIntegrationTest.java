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
import org.program.pair.domain.map.dto.MapCluster;
import org.program.pair.domain.map.dto.MapMarkersResponse;
import org.program.pair.domain.map.dto.MapProgramDto;
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
import java.util.UUID;
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

    // Fixtures des compteurs, posées hors de la fenêtre de longitude et de la bbox
    // des tests de bornage, pour qu'aucun des deux jeux ne perturbe l'autre.
    private static final double TRIPLE_LAT = 5.0;
    private static final double TRIPLE_LNG = -20.0;
    private static final double DUO_LAT = 6.0;
    private static final double DUO_LNG = -20.0;

    // Zone dédiée à GET /map/bounds, isolée des deux jeux précédents.
    private static final double BOUNDS_LAT = 20.0;
    private static final double BOUNDS_LNG = -40.0;
    private static boolean boundsFixturesCreated = false;

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

    // — agrégation (demande 5, option B) —

    @Test
    void sansZoom_aucuneAgregation() {
        MapActivitiesResponse response = fetch(atlanticBbox(null));

        assertThat(response.clusters()).isEmpty();
        assertThat(response.activities()).hasSize(2);
    }

    @Test
    void zoomFaible_doitAgregerLesMarqueursProches_avecLeursBornes() {
        // Maille de 1° au zoom 7 : les deux fixtures (0,539° d'écart en latitude)
        // tombent dans la même cellule.
        MapActivitiesResponse response = fetch(atlanticBbox(7));

        assertThat(response.activities()).as("plus rien de non agrégé").isEmpty();
        assertThat(response.clusters()).hasSize(1);

        MapCluster cluster = response.clusters().get(0);
        assertThat(cluster.count()).isEqualTo(2);
        assertThat(cluster.type()).isEqualTo("cluster");

        // Les bornes portent l'étendue réelle des membres — c'est ce qui permet
        // au client de recadrer sur le cluster au tap.
        assertThat(cluster.boundsSouth()).isEqualTo(ORIGIN_LAT);
        assertThat(cluster.boundsNorth()).isEqualTo(FAR_LAT);
        assertThat(cluster.boundsWest()).isEqualTo(ORIGIN_LNG);
        assertThat(cluster.boundsEast()).isEqualTo(FAR_LNG);

        assertThat(cluster.latitude())
            .as("le centre doit tomber dans les bornes")
            .isBetween(cluster.boundsSouth(), cluster.boundsNorth());
    }

    @Test
    void zoomMaximal_neDoitPlusRenvoyerQueDesActivites() {
        // Critère du prompt client : au zoom maximum, plus aucun cluster.
        MapActivitiesResponse response = fetch(atlanticBbox(20));

        assertThat(response.clusters()).isEmpty();
        assertThat(response.activities()).hasSize(2);
    }

    @Test
    void sommeDesClustersEtDesActivites_doitEgalerTotalInBounds() {
        for (int zoom : new int[]{3, 7, 12, 20}) {
            MapActivitiesResponse response = fetch(atlanticBbox(zoom));

            int aggregated = response.clusters().stream().mapToInt(MapCluster::count).sum();
            assertThat(aggregated + response.activities().size())
                .as("zoom %d : rien ne doit être perdu ni compté deux fois", zoom)
                .isEqualTo(response.totalInBounds());
            assertThat(response.truncated()).isFalse();
        }
    }

    @Test
    void zoomHorsBornes_doitEtreRefuse() {
        expectError(b -> b.path("/api/map/activities")
            .queryParam("zoom", 25)
            .build(), "MAP_ZOOM_OUT_OF_RANGE");
    }

    @Test
    void clustersDUtilisateurs_doiventAussiPorterLeursBornes() {
        // /map/clusters agrège des utilisateurs, pas des activités : le contenu
        // dépend des données de seed. On vérifie donc l'invariant de forme, qui
        // lui ne dépend de rien — un cluster sans bornes reste inexploitable.
        List<MapCluster> clusters = webTestClient.get()
            .uri(b -> b.path("/api/map/clusters")
                .queryParam("south", -90).queryParam("north", 90)
                .queryParam("west", -180).queryParam("east", 180)
                .queryParam("zoom", 5)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(MapCluster.class)
            .returnResult()
            .getResponseBody();

        assertThat(clusters).isNotNull();
        for (MapCluster cluster : clusters) {
            assertThat(cluster.boundsSouth()).isNotNull();
            assertThat(cluster.boundsNorth()).isNotNull();
            assertThat(cluster.boundsWest()).isNotNull();
            assertThat(cluster.boundsEast()).isNotNull();
            assertThat(cluster.boundsSouth()).isLessThanOrEqualTo(cluster.boundsNorth());
            assertThat(cluster.boundsWest()).isLessThanOrEqualTo(cluster.boundsEast());
            assertThat(cluster.latitude()).isBetween(cluster.boundsSouth(), cluster.boundsNorth());
            assertThat(cluster.longitude()).isBetween(cluster.boundsWest(), cluster.boundsEast());
        }
    }

    // — programCount / scheduleCount —

    @Test
    void programCount_doitCompterDesProgrammes_pasDesCreneaux() {
        // Un programme unique à trois séances hebdomadaires au même lieu : le
        // compteur affichait « 3 programmes », y compris dans la confirmation de
        // suppression d'une activité côté client.
        createSchedulesForOneProgram("Carte bornage — hebdo", TRIPLE_LAT, TRIPLE_LNG, 3);

        MapActivityMarkerDto marker = fetchMarkerAt(TRIPLE_LAT);

        assertThat(marker.programCount()).isEqualTo(1);
        assertThat(marker.scheduleCount())
            .as("le nombre de créneaux reste exposé, sous son vrai nom")
            .isEqualTo(3);
    }

    @Test
    void programCount_doitCompterChaqueProgrammeUneFois_quandPlusieursCoexistent() {
        createSchedulesForOneProgram("Carte bornage — duo A", DUO_LAT, DUO_LNG, 2);
        createSchedulesForOneProgram("Carte bornage — duo B", DUO_LAT, DUO_LNG, 1);

        MapActivityMarkerDto marker = fetchMarkerAt(DUO_LAT);

        assertThat(marker.programCount()).isEqualTo(2);
        assertThat(marker.scheduleCount()).isEqualTo(3);
    }

    @Test
    void scheduleCount_doitToujoursEtreSuperieurOuEgalAProgramCount() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities").build());

        assertThat(response.activities()).isNotEmpty();
        for (MapActivityMarkerDto marker : response.activities()) {
            assertThat(marker.scheduleCount())
                .as("marqueur %s", marker.activityName())
                .isGreaterThanOrEqualTo(marker.programCount());
            assertThat(marker.programCount()).isPositive();
        }
    }

    // — GET /map/bounds : truncated / totalInBounds, par symétrie avec /map/activities —
    //
    // Ces tests vivent ici, et non dans une classe dédiée, pour réutiliser le
    // compte de la classe : l'inscription est plafonnée à 5/heure/IP et le
    // budget est partagé entre toutes les classes de test.

    @Test
    void bounds_sansTroncature_doitRendreTouteLaZone() {
        createBoundsFixtures();

        MapMarkersResponse response = fetchBounds(100, 0);

        assertThat(response.programs()).hasSize(3);
        assertThat(response.truncated()).isFalse();
        assertThat(response.totalInBounds()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void bounds_avecLimit_doitSignalerLaTroncature() {
        createBoundsFixtures();

        MapMarkersResponse response = fetchBounds(1, 0);

        assertThat(response.programs()).hasSize(1);
        assertThat(response.truncated())
            .as("deux programmes de la zone ont été écartés")
            .isTrue();
    }

    @Test
    void bounds_deuxPagesSuccessives_neDoiventPasSeRecouvrir() {
        createBoundsFixtures();

        List<UUID> page0 = fetchBounds(2, 0).programs().stream().map(MapProgramDto::id).toList();
        List<UUID> page1 = fetchBounds(2, 2).programs().stream().map(MapProgramDto::id).toList();

        assertThat(page0).hasSize(2);
        assertThat(page1).hasSize(1);
        assertThat(page0).doesNotContainAnyElementsOf(page1);
    }

    @Test
    void bounds_deuxAppelsIdentiques_doiventRendreLaMemePage() {
        createBoundsFixtures();

        assertThat(fetchBounds(2, 0).programs().stream().map(MapProgramDto::id).toList())
            .isEqualTo(fetchBounds(2, 0).programs().stream().map(MapProgramDto::id).toList());
    }

    @Test
    void bounds_bornesInversees_doiventEtreRefusees() {
        webTestClient.get()
            .uri(b -> b.path("/api/map/bounds")
                .queryParam("south", BOUNDS_LAT + 1).queryParam("north", BOUNDS_LAT - 1)
                .queryParam("west", BOUNDS_LNG - 1).queryParam("east", BOUNDS_LNG + 1)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MAP_BOUNDS_INVALID");
    }

    @Test
    void bounds_limitNul_doitEtreRefuse() {
        webTestClient.get()
            .uri(b -> b.path("/api/map/bounds")
                .queryParam("south", BOUNDS_LAT - 1).queryParam("north", BOUNDS_LAT + 1)
                .queryParam("west", BOUNDS_LNG - 1).queryParam("east", BOUNDS_LNG + 1)
                .queryParam("limit", 0)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MAP_LIMIT_OUT_OF_RANGE");
    }

    // — helpers —

    private MapMarkersResponse fetchBounds(int limit, int offset) {
        return webTestClient.get()
            .uri(b -> b.path("/api/map/bounds")
                .queryParam("south", BOUNDS_LAT - 1).queryParam("north", BOUNDS_LAT + 1)
                .queryParam("west", BOUNDS_LNG - 1).queryParam("east", BOUNDS_LNG + 1)
                .queryParam("limit", limit).queryParam("offset", offset)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapMarkersResponse.class)
            .returnResult()
            .getResponseBody();
    }

    /** Trois programmes d'un créneau chacun, dans une zone isolée des seeds. */
    private void createBoundsFixtures() {
        if (boundsFixturesCreated) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            createSchedulesForOneProgram(
                "Carte bounds — " + i, BOUNDS_LAT + i * 0.01, BOUNDS_LNG, 1);
        }
        boundsFixturesCreated = true;
    }

    /** Le marqueur posé à cette latitude, cherché sans filtre géographique. */
    private MapActivityMarkerDto fetchMarkerAt(double lat) {
        return fetch(b -> b.path("/api/map/activities").build()).activities().stream()
            .filter(m -> m.lat() == lat)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Aucun marqueur à la latitude " + lat));
    }

    /** Bbox isolant les deux fixtures atlantiques, avec un zoom optionnel. */
    private Function<UriBuilder, java.net.URI> atlanticBbox(Integer zoom) {
        return b -> {
            b.path("/api/map/activities")
                .queryParam("south", 9.0).queryParam("north", 11.0)
                .queryParam("west", -31.0).queryParam("east", -29.0);
            if (zoom != null) {
                b.queryParam("zoom", zoom);
            }
            return b.build();
        };
    }

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

    /** Un programme unique portant {@code count} créneaux au même lieu. */
    private void createSchedulesForOneProgram(String title, double lat, double lng, int count) {
        User owner = userRepository.findByEmail(HOST_EMAIL).orElseThrow();
        Program program = createProgram(owner, title);
        for (int i = 0; i < count; i++) {
            saveSchedule(program, title + " #" + i, lat, lng);
        }
    }

    private void createLocatedSchedule(User owner, String title, double lat, double lng) {
        saveSchedule(createProgram(owner, title), title, lat, lng);
    }

    private Program createProgram(User owner, String title) {
        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository
            .findByUserIdAndActivityId(owner.getId(), activity.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(owner).activity(activity).build()));

        return programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    private void saveSchedule(Program program, String placeName, double lat, double lng) {
        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName(placeName)
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
