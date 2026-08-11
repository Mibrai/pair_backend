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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le compte affiché sur une pastille doit être celui des marqueurs réellement
 * affichables.
 *
 * <p>Symptôme rapporté : une pastille annonçait 12, on zoomait, il restait
 * 7 pins. Le client applique la règle produit — « seulement les activités ayant
 * une séance à venir » — sur les marqueurs isolés, les seuls à porter les champs
 * nécessaires ; un cluster ne porte que {@code count}, donc il l'affichait tel
 * quel. La même carte fonctionnait à deux régimes.
 *
 * <p>Ces tests verrouillent l'unique définition : le filtre s'applique
 * <b>avant</b> l'agrégation, donc aux deux listes à la fois, et
 * {@code includeExpired=true} reste la sortie de secours pour un consommateur
 * qui dépendrait de l'ancienne population.
 *
 * <p>Les fixtures vivent loin de toute zone peuplée par les seeds, et chaque
 * assertion passe par une bbox : les comptes doivent être exacts, pas
 * approximatifs.
 */
class MapUpcomingFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // Zone mixte : deux activités à venir et une expirée. Les trois latitudes
    // partagent la cellule du zoom 7 (maille 1°) et se séparent au zoom 20
    // (maille 0,01°) — c'est ce qui permet de tester l'agrégation et son absence
    // sur le même jeu de données.
    private static final double MIXED_LNG = -50.0;
    private static final double UPCOMING_A_LAT = 30.10;
    private static final double UPCOMING_B_LAT = 30.50;
    private static final double EXPIRED_LAT = 30.80;

    // Zone dont toutes les activités sont expirées, isolée de la précédente.
    private static final double EMPTY_ZONE_LAT = 40.30;
    private static final double EMPTY_ZONE_LNG = -60.0;

    private static final String HOST_EMAIL = "map-upcoming-host@pair.app";

    private static boolean fixturesCreated = false;
    private static String token;

    @BeforeEach
    void setUp() {
        if (fixturesCreated) {
            return;
        }
        token = registerAndLogin(HOST_EMAIL);
        User host = userRepository.findByEmail(HOST_EMAIL).orElseThrow();

        createSchedule(host, "yoga", "Yoga — séance à venir",
            UPCOMING_A_LAT, MIXED_LNG, Instant.now().plus(2, ChronoUnit.DAYS));
        createSchedule(host, "tennis", "Tennis — séance à venir",
            UPCOMING_B_LAT, MIXED_LNG, Instant.now().plus(3, ChronoUnit.DAYS));
        createSchedule(host, "natation", "Natation — dernière séance passée",
            EXPIRED_LAT, MIXED_LNG, Instant.now().minus(5, ChronoUnit.DAYS));

        createSchedule(host, "escalade", "Escalade — passée",
            EMPTY_ZONE_LAT, EMPTY_ZONE_LNG, Instant.now().minus(10, ChronoUnit.DAYS));
        createSchedule(host, "football", "Football — passé",
            EMPTY_ZONE_LAT + 0.2, EMPTY_ZONE_LNG, Instant.now().minus(9, ChronoUnit.DAYS));

        fixturesCreated = true;
    }

    /**
     * La demande, en un test : la pastille ne doit compter que ce qui reste
     * après dépliage. Trois marqueurs dans la cellule, dont un expiré ⇒
     * {@code count == 2}, pas 3.
     */
    @Test
    void leCountDUnCluster_neDoitCompterQueLesActivitesAvecSeanceAVenir() {
        MapActivitiesResponse response = fetch(mixedZone(7));

        assertThat(response.activities()).as("tout est agrégé à cette maille").isEmpty();
        assertThat(response.clusters()).hasSize(1);

        MapCluster cluster = response.clusters().get(0);
        assertThat(cluster.count()).isEqualTo(2);
        assertThat(cluster.activityIds()).hasSize(2);

        // Les bornes ne doivent pas non plus porter la trace de l'expirée :
        // recadrer sur la pastille amènerait sinon sur une zone vide.
        assertThat(cluster.boundsNorth()).isEqualTo(UPCOMING_B_LAT);
        assertThat(cluster.boundsSouth()).isEqualTo(UPCOMING_A_LAT);
    }

    /**
     * Le même jeu de données à un zoom qui n'agrège pas. C'est ce test qui prouve
     * que les deux chemins partagent une seule définition : si le filtre n'était
     * appliqué qu'à l'agrégation, l'expirée réapparaîtrait ici.
     */
    @Test
    void sansAgregation_lActiviteExpireeDoitEtreAbsenteDesActivites() {
        MapActivitiesResponse response = fetch(mixedZone(20));

        assertThat(response.clusters()).isEmpty();
        assertThat(response.activities()).hasSize(2);
        assertThat(response.activities())
            .allSatisfy(marker -> assertThat(marker.nextSessionAt()).isNotNull());
        assertThat(response.activities())
            .extracting(MapActivityMarkerDto::activityName)
            .doesNotContain("Natation");
    }

    /** Et sans paramètre de zoom du tout — troisième chemin, même population. */
    @Test
    void sansZoom_lActiviteExpireeDoitEtreAbsente() {
        MapActivitiesResponse response = fetch(mixedZone(null));

        assertThat(response.clusters()).isEmpty();
        assertThat(response.activities()).hasSize(2);
    }

    /**
     * L'invariant dont dépend le bandeau « vous ne voyez pas tout » : après
     * filtrage, {@code totalInBounds} doit décrire la même population que les
     * deux listes réunies.
     */
    @Test
    void sommeDesClustersEtDesActivites_doitEgalerTotalInBounds() {
        for (Integer zoom : new Integer[]{null, 3, 7, 12, 20}) {
            MapActivitiesResponse response = fetch(mixedZone(zoom));

            int aggregated = response.clusters().stream().mapToInt(MapCluster::count).sum();
            assertThat(aggregated + response.activities().size())
                .as("zoom %s : rien de perdu, rien compté deux fois", zoom)
                .isEqualTo(response.totalInBounds());
            assertThat(response.totalInBounds()).isEqualTo(2);
            assertThat(response.truncated()).isFalse();
        }
    }

    /**
     * Une zone dont tout est écarté rend une réponse vide — et surtout pas un
     * cluster de {@code count: 0}, qui dessinerait une pastille sur du vide.
     */
    @Test
    void zoneEntierementExpiree_doitRendreUneReponseVide() {
        for (Integer zoom : new Integer[]{null, 7, 20}) {
            MapActivitiesResponse response = fetch(emptyZone(zoom));

            assertThat(response.activities()).as("zoom %s", zoom).isEmpty();
            assertThat(response.clusters()).as("zoom %s", zoom).isEmpty();
            assertThat(response.totalInBounds()).as("zoom %s", zoom).isZero();
            assertThat(response.truncated()).isFalse();
        }
    }

    /**
     * La sortie de secours. La route étant publique, un consommateur que nous ne
     * connaissons pas peut dépendre de la population d'avant : il doit pouvoir la
     * retrouver, dans les deux listes comme dans les compteurs.
     */
    @Test
    void includeExpired_doitRetablirLaPopulationComplete() {
        MapActivitiesResponse aggregated = fetch(b -> mixedZoneBuilder(b, 7)
            .queryParam("includeExpired", true).build());

        assertThat(aggregated.clusters()).hasSize(1);
        assertThat(aggregated.clusters().get(0).count()).isEqualTo(3);
        assertThat(aggregated.totalInBounds()).isEqualTo(3);

        MapActivitiesResponse loose = fetch(b -> mixedZoneBuilder(b, 20)
            .queryParam("includeExpired", true).build());

        assertThat(loose.activities()).hasSize(3);
        assertThat(loose.activities())
            .extracting(MapActivityMarkerDto::activityName)
            .contains("Natation");

        // includeExpired=false doit valoir le défaut, pas l'inverse du défaut.
        MapActivitiesResponse explicitFalse = fetch(b -> mixedZoneBuilder(b, 20)
            .queryParam("includeExpired", false).build());
        assertThat(explicitFalse.activities()).hasSize(2);
    }

    /**
     * La seconde condition de la règle produit — « a au moins un programme » —
     * est structurellement vraie sur cette route : un marqueur naît d'un créneau
     * localisé rattaché à un programme. Ce test empêche cette invariance de
     * rester tacite : si elle cessait un jour d'être vraie, le filtre devrait
     * gagner une condition.
     */
    @Test
    void toutMarqueurRenvoye_doitPorterAuMoinsUnProgramme() {
        MapActivitiesResponse response = fetch(b -> b.path("/api/map/activities").build());

        assertThat(response.activities()).isNotEmpty();
        assertThat(response.activities())
            .allSatisfy(marker -> {
                assertThat(marker.programCount()).isGreaterThanOrEqualTo(1);
                assertThat(marker.scheduleCount()).isGreaterThanOrEqualTo(1);
            });
    }

    // — helpers —

    private Function<UriBuilder, java.net.URI> mixedZone(Integer zoom) {
        return b -> mixedZoneBuilder(b, zoom).build();
    }

    private UriBuilder mixedZoneBuilder(UriBuilder builder, Integer zoom) {
        UriBuilder withBbox = builder.path("/api/map/activities")
            .queryParam("south", 29.5).queryParam("north", 31.5)
            .queryParam("west", -50.5).queryParam("east", -49.5);
        return zoom != null ? withBbox.queryParam("zoom", zoom) : withBbox;
    }

    private Function<UriBuilder, java.net.URI> emptyZone(Integer zoom) {
        return b -> {
            UriBuilder withBbox = b.path("/api/map/activities")
                .queryParam("south", 39.5).queryParam("north", 41.5)
                .queryParam("west", -60.5).queryParam("east", -59.5);
            return (zoom != null ? withBbox.queryParam("zoom", zoom) : withBbox).build();
        };
    }

    private MapActivitiesResponse fetch(Function<UriBuilder, java.net.URI> uri) {
        MapActivitiesResponse response = webTestClient.get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(response).isNotNull();
        return response;
    }

    private void createSchedule(User owner, String activitySlug, String title,
                                double lat, double lng, Instant startsAt) {
        Activity activity = activityRepository.findBySlug(activitySlug).orElseThrow();
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

        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName(title)
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue de la Maille")
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
