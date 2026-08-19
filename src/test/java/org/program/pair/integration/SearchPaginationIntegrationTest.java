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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Demande 2 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md : pagination de
 * POST /search, avec l'arbitrage (a) validé par le client — plafonds relevés,
 * fusion, découpe, totalCount exact dans la limite du plafond de candidats.
 *
 * <p>Un test par critère d'acceptation.
 *
 * <p>Le modèle d'embeddings est forcé au vecteur nul pour emprunter le chemin
 * plein texte, déterministe. Une seule inscription : les organisateurs sont
 * créés en base et n'ont jamais besoin de jeton.
 */
class SearchPaginationIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final double LAT = 12.0;
    private static final double LNG = 55.0;
    private static final String QUERY = "pagination";
    private static final String EMAIL = "search-pagination@pair.app";

    private static String token;
    private static boolean fixturesCreated = false;

    @BeforeEach
    void setUp() {
        if (token == null) {
            token = registerAndLogin(EMAIL);
        }
        if (!fixturesCreated) {
            createSeven();
            fixturesCreated = true;
        }
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
    }

    @Test
    void deuxPages_neDoiventNiSeRecouvrirNiPerdreUnResultat() {
        SearchResponse page0 = search(0, 3);
        SearchResponse page1 = search(1, 3);

        assertThat(ids(page0)).hasSize(3);
        assertThat(ids(page0)).doesNotContainAnyElementsOf(ids(page1));
    }

    @Test
    void lOrdre_doitEtreStableEntreDeuxAppelsIdentiques() {
        assertThat(ids(search(0, 5))).isEqualTo(ids(search(0, 5)));
    }

    @Test
    void totalCount_doitEtreConstantDunePageALautre() {
        int onPage0 = search(0, 2).totalCount();
        int onPage2 = search(2, 2).totalCount();

        assertThat(onPage0).isEqualTo(onPage2);
        assertThat(onPage0).isGreaterThanOrEqualTo(7);
    }

    @Test
    void hasMore_doitEtreFauxSurLaDernierePage() {
        int total = search(0, 100).totalCount();

        assertThat(search(0, 100).hasMore())
            .as("tout tient sur une page")
            .isFalse();
        assertThat(search(0, 2).hasMore()).isTrue();

        // Dernière page d'un découpage qui ne tombe pas juste.
        int lastPage = (total - 1) / 3;
        assertThat(search(lastPage, 3).hasMore()).isFalse();
    }

    @Test
    void hasMore_doitEtreFaux_memeQuandTotalCountEstUnMultipleExactDePageSize() {
        // Le piège classique : avec 6 résultats et pageSize=3, la page 1 est la
        // dernière — hasMore ne doit pas rester vrai sous prétexte qu'elle est
        // pleine.
        int total = search(0, 100).totalCount();
        int pageSize = total;                       // une seule page, exactement pleine

        SearchResponse full = search(0, pageSize);
        assertThat(full.results()).hasSize(total);
        assertThat(full.hasMore()).isFalse();
    }

    @Test
    void sansPageNiPageSize_leComportementResteCeluiDavant_avecTotalCountEnPlus() {
        SearchResponse implicit = searchWithoutPagination();

        assertThat(implicit.page()).isZero();
        assertThat(implicit.pageSize())
            .as("20, la taille que la route renvoyait avant d'être paginée")
            .isEqualTo(20);
        assertThat(implicit.totalCount()).isNotNull().isPositive();
        assertThat(ids(implicit)).isEqualTo(ids(search(0, 20)));
    }

    @Test
    void countsByType_doitSommerATotalCount() {
        SearchResponse response = search(0, 2);

        assertThat(response.countsByType())
            .as("les trois clés sont toujours présentes, à zéro le cas échéant")
            .containsKeys("user", "program", "slot");
        assertThat(response.countsByType().values().stream().mapToInt(Integer::intValue).sum())
            .isEqualTo(response.totalCount());
    }

    @Test
    void countsByType_doitCompterLaRequete_pasLaPage() {
        assertThat(search(0, 2).countsByType()).isEqualTo(search(2, 2).countsByType());
    }

    @Test
    void uneReponseSansResultat_neDoitPasEtrePaginee() {
        // Distinguer « pas de pagination ici » de « zéro résultat sur cette page ».
        SearchResponse empty = post("kitesurf à Oulan-Bator", 0, 20);

        assertThat(empty.type()).isEqualTo("empty");
        assertThat(empty.totalCount()).isNull();
        assertThat(empty.page()).isNull();
        assertThat(empty.pageSize()).isNull();
        assertThat(empty.hasMore()).isNull();
        assertThat(empty.countsByType()).isNull();
    }

    @Test
    void unePageAuDelaDuTotal_doitRenvoyerUneListeVideSansErreur() {
        SearchResponse far = search(50, 20);

        assertThat(far.results()).isEmpty();
        assertThat(far.hasMore()).isFalse();
        assertThat(far.totalCount()).isPositive();
    }

    // — helpers —

    private List<UUID> ids(SearchResponse response) {
        return response.results().stream().map(SearchResultDto::id).toList();
    }

    private SearchResponse search(int page, int pageSize) {
        return post(QUERY, page, pageSize);
    }

    private SearchResponse post(String query, int page, int pageSize) {
        return exchange(new SearchRequest(query, LAT, LNG, 50_000, page, pageSize, null));
    }

    private SearchResponse searchWithoutPagination() {
        return exchange(new SearchRequest(QUERY, LAT, LNG, 50_000));
    }

    private SearchResponse exchange(SearchRequest body) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();
    }

    /** Sept programmes portant le même mot, dans une zone isolée des seeds. */
    private void createSeven() {
        Activity activity = activityRepository.findAll().get(0);
        for (int i = 0; i < 7; i++) {
            User owner = userRepository.save(User.builder()
                .email("search-pagination-" + i + "@pair.app")
                .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
                .displayName("Hôte pagination " + i)
                .isActive(true)
                .locationPublic(true)
                .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
                .build());

            UserActivity userActivity = userActivityRepository.save(
                UserActivity.builder().user(owner).activity(activity).visibleOnMap(true).build());

            Program program = programRepository.save(Program.builder()
                .userActivity(userActivity)
                .title("Programme " + QUERY + " numéro " + i)
                .description("Un programme de " + QUERY + " pour tester la découpe en pages.")
                .status(ProgramStatus.ACTIVE)
                .isPublic(true)
                .build());

            Instant startsAt = Instant.now().plus(2 + i, ChronoUnit.DAYS);
            scheduleRepository.save(Schedule.builder()
                .program(program)
                .placeName("Salle " + i)
                .placeType(PlaceType.PUBLIC)
                .addressPublic("1 rue de la Pagination")
                .showExactAddress(true)
                .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
                .startsAt(startsAt)
                .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
                .maxParticipants(8)
                .isOpenToPartners(true)
                .status(SlotStatus.OPEN)
                .build());
        }
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
