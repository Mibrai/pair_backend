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
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Un résultat {@code program} doit porter le lieu de sa séance, jamais le
 * domicile de son organisateur.
 *
 * <p>Reproduit le rapport client du 2026-08-15 avec ses coordonnées réelles :
 * un utilisateur à Herne, un organisateur dont le profil est à Berlin, une
 * séance à Gelsenkirchen. La recherche annonçait <b>448 km</b> — la distance
 * Herne–Berlin — pour un cours qui se tient à quatre kilomètres. Les fixtures
 * gardent ces valeurs plutôt que des nombres ronds : quand un test échouera,
 * l'écart se lira directement comme le symptôme rapporté.
 *
 * <p>Le défaut ne se limitait pas à l'affichage. La position de l'organisateur
 * servait aussi à <b>sélectionner</b> : le filtre de rayon et le tri portaient
 * sur elle. Un programme voisin dont l'organisateur habitait ailleurs était donc
 * simplement absent — et une absence, contrairement à une distance fausse, ne se
 * remarque pas. C'est {@link #programmeVoisin_doitEtreTrouve_memeSiLOrganisateurEstLoin}
 * qui verrouille ce point, et c'est le plus important de ce fichier.
 *
 * <p>Le vecteur d'embedding est neutralisé, comme dans les autres tests de
 * recherche : le chemin exercé est celui des requêtes SQL, celui-là même qui
 * portait le défaut.
 */
class SearchProgramDistanceIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // Les coordonnées du rapport client, à l'identique.
    private static final double HERNE_LAT = 51.5742,  HERNE_LNG = 7.0273;
    private static final double BERLIN_LAT = 52.52,   BERLIN_LNG = 13.405;
    private static final double GELSEN_LAT = 51.5513825, GELSEN_LNG = 7.0758985;
    private static final double DORTMUND_LAT = 51.5136, DORTMUND_LNG = 7.4653;

    /** Herne → Gelsenkirchen. Herne → Berlin en vaut 448 484 : les deux ne se confondent pas. */
    private static final double GELSEN_DISTANCE_M = 4_210;

    private static final String HOST_EMAIL = "prog-distance-host@pair.app";
    private static final String SEARCHER_EMAIL = "prog-distance-searcher@pair.app";

    private static boolean accountsCreated = false;
    private static String searcherToken;
    private User host;

    @BeforeEach
    void setUp() {
        if (!accountsCreated) {
            registerAndLogin(HOST_EMAIL);
            searcherToken = registerAndLogin(SEARCHER_EMAIL);
            accountsCreated = true;
        }
        host = userRepository.findByEmail(HOST_EMAIL).orElseThrow();
        // L'organisateur habite Berlin : c'est cette position que la recherche
        // rendait, et qu'aucune assertion de ce fichier ne doit revoir.
        host.setLocation(geometryFactory.createPoint(new Coordinate(BERLIN_LNG, BERLIN_LAT)));
        userRepository.save(host);
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
    }

    /** La demande du client, littéralement. */
    @Test
    void leProgramme_doitPorterLeLieuDeSaSeance_pasCeluiDeSonOrganisateur() {
        String title = "Yoga Gelsenkirchen " + UUID.randomUUID();
        createProgramWithSchedules(title, null, List.of(new double[]{GELSEN_LAT, GELSEN_LNG}));

        SearchResultDto result = searchAndFind(title, 50_000);

        assertThat(result.lat()).isEqualTo(GELSEN_LAT, within(1e-6));
        assertThat(result.lng()).isEqualTo(GELSEN_LNG, within(1e-6));
        assertThat(result.distanceMeters())
            .as("la distance réelle, pas les 448 km qui séparent Herne de Berlin")
            .isEqualTo(GELSEN_DISTANCE_M, within(50.0));
    }

    /**
     * Le défaut invisible, et la raison pour laquelle corriger la seule
     * coordonnée affichée n'aurait pas suffi.
     *
     * <p>Rayon de dix kilomètres : la séance y est (4,2 km), l'organisateur non
     * (448 km). Avant, le filtre portait sur l'organisateur et le programme
     * n'apparaissait pas du tout.
     */
    @Test
    void programmeVoisin_doitEtreTrouve_memeSiLOrganisateurEstLoin() {
        String title = "Yoga proche " + UUID.randomUUID();
        createProgramWithSchedules(title, null, List.of(new double[]{GELSEN_LAT, GELSEN_LNG}));

        SearchResultDto result = searchAndFind(title, 10_000);

        assertThat(result.distanceMeters()).isLessThan(10_000);
    }

    /**
     * Le pendant : un rayon serré doit encore exclure ce qui est réellement
     * loin. Sans ce test, supprimer tout filtre géographique passerait.
     */
    @Test
    void programmeLointain_doitResterExclu_dUnRayonSerre() {
        String title = "Yoga Dortmund " + UUID.randomUUID();
        createProgramWithSchedules(title, null, List.of(new double[]{DORTMUND_LAT, DORTMUND_LNG}));

        // Dortmund est à 31 km de Herne : hors d'un rayon de 10 km.
        assertThat(searchTitles(title, 10_000)).isEmpty();
        // …et dedans à 50 km, ce qui prouve que l'absence précédente tient au
        // rayon et non à un programme introuvable.
        assertThat(searchTitles(title, 50_000)).isNotEmpty();
    }

    /**
     * Plusieurs lieux, un seul résultat : celui qui répond à « à quelle distance
     * de moi ». Le programme se tient à Gelsenkirchen et à Dortmund ; c'est le
     * premier qui doit le situer.
     */
    @Test
    void programmeAPlusieursLieux_doitEtreSitueAuPlusProche() {
        String title = "Yoga deux villes " + UUID.randomUUID();
        createProgramWithSchedules(title, null, List.of(
            new double[]{DORTMUND_LAT, DORTMUND_LNG},
            new double[]{GELSEN_LAT, GELSEN_LNG}));

        SearchResultDto result = searchAndFind(title, 50_000);

        assertThat(result.lat()).isEqualTo(GELSEN_LAT, within(1e-6));
        assertThat(result.distanceMeters()).isEqualTo(GELSEN_DISTANCE_M, within(50.0));
    }

    /**
     * Le cas que le client a demandé de ne surtout pas replier : sans séance
     * localisée, le programme reste trouvable mais ne prétend pas être quelque
     * part. C'est le repli silencieux sur l'organisateur qui rendait le défaut
     * indétectable depuis les journaux.
     */
    @Test
    void programmeSansSeanceLocalisee_doitEtreRenduSansCoordonnees() {
        String title = "Yoga sans lieu " + UUID.randomUUID();
        createProgramWithSchedules(title, null, List.of());

        SearchResultDto result = searchAndFind(title, 10_000);

        assertThat(result.lat()).isNull();
        assertThat(result.lng()).isNull();
        assertThat(result.distanceMeters()).isNull();
    }

    /**
     * Un programme à distance n'a pas de lieu — même si une séance localisée
     * traîne en base, ce qu'une saisie approximative produit vite. La modalité
     * l'emporte, sinon on afficherait une distance pour quelque chose qui se
     * suit depuis chez soi.
     */
    @Test
    void programmeADistance_neDoitPorterNiLieuNiDistance() {
        String title = "Yoga en ligne " + UUID.randomUUID();
        createProgramWithSchedules(title, LocationType.ONLINE,
            List.of(new double[]{GELSEN_LAT, GELSEN_LNG}));

        SearchResultDto result = searchAndFind(title, 10_000);

        assertThat(result.lat()).isNull();
        assertThat(result.lng()).isNull();
        assertThat(result.distanceMeters()).isNull();
    }

    /**
     * La requête native qui sert le chemin sémantique, éprouvée contre une vraie
     * base : {@code DISTINCT ON} ne garantit « la plus proche » que si le tri
     * l'accompagne, et c'est le genre de détail qu'un test unitaire ne voit pas.
     */
    @Test
    void laRequeteDeLieu_doitRendreUneSeuleLigneParProgramme_laPlusProche() {
        String title = "Yoga requête " + UUID.randomUUID();
        Program program = createProgramWithSchedules(title, null, List.of(
            new double[]{DORTMUND_LAT, DORTMUND_LNG},
            new double[]{GELSEN_LAT, GELSEN_LNG}));

        List<Object[]> rows = scheduleRepository.findNearestVenuesByProgramIds(
            List.of(program.getId()), HERNE_LAT, HERNE_LNG);

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[1]).doubleValue()).isEqualTo(GELSEN_LAT, within(1e-6));
        assertThat(((Number) rows.get(0)[3]).doubleValue()).isEqualTo(GELSEN_DISTANCE_M, within(50.0));
    }

    // — fixtures —

    private Program createProgramWithSchedules(String title, LocationType locationType,
                                                List<double[]> latLngs) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository
            .findByUserIdAndActivityId(host.getId(), yoga.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(host).activity(yoga).build()));

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .locationType(locationType)
            .build());

        for (double[] latLng : latLngs) {
            Instant startsAt = Instant.now().plus(3, ChronoUnit.DAYS);
            scheduleRepository.save(Schedule.builder()
                .program(program)
                .placeName("Studio " + latLng[0])
                .placeType(PlaceType.PUBLIC)
                .addressPublic("1 Teststraße")
                .showExactAddress(true)
                .location(geometryFactory.createPoint(new Coordinate(latLng[1], latLng[0])))
                .startsAt(startsAt)
                .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
                .maxParticipants(8)
                .isOpenToPartners(true)
                .status(SlotStatus.OPEN)
                .build());
        }
        return program;
    }

    // — appels —

    private SearchResultDto searchAndFind(String title, int radiusMeters) {
        return search(radiusMeters).results().stream()
            .filter(r -> "program".equals(r.resultType()) && title.equals(r.title()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Programme '" + title + "' absent des résultats (rayon " + radiusMeters + " m)"));
    }

    private List<String> searchTitles(String title, int radiusMeters) {
        SearchResponse response = search(radiusMeters);
        if (response.results() == null) {
            return List.of();
        }
        return response.results().stream()
            .map(SearchResultDto::title)
            .filter(title::equals)
            .toList();
    }

    private SearchResponse search(int radiusMeters) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(h -> h.setBearerAuth(searcherToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest("yoga", HERNE_LAT, HERNE_LNG, radiusMeters))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();
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
