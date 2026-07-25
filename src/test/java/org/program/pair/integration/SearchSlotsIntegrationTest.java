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
import org.program.pair.domain.search.EmbeddingService;
import org.program.pair.domain.search.LlmIntentExtractor;
import org.program.pair.domain.search.dto.SearchIntent;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.dto.SearchResultDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * POST /api/search doit désormais renvoyer des créneaux (resultType="slot"),
 * pas seulement des personnes/programmes — voir docs/specs/BACKEND_SEARCH_SLOTS.md.
 * Le LLM est mocké pour un test déterministe (comme SemanticSearchIntegrationTest).
 *
 * N'enregistre que 2 comptes pour toute la classe (une fois, via un garde
 * statique) : l'inscription est limitée à 5/heure/IP (RateLimiterService), un
 * budget vite épuisé si chaque test enregistre les siens. Note : pas de
 * @TestInstance(PER_CLASS)/@BeforeAll ici — ça casse l'ordre d'initialisation
 * de @Testcontainers (le port du conteneur n'est pas encore mappé quand
 * Spring construit le contexte).
 */
class SearchSlotsIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LlmIntentExtractor intentExtractor;
    @MockitoBean EmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double LAT = 48.8566;
    private static final double LNG = 2.3522;

    private static boolean accountsCreated = false;
    private static String searcherToken;
    private User searcher;
    private User host;

    @BeforeEach
    void setUpSharedAccounts() {
        if (!accountsCreated) {
            searcherToken = registerAndLogin("slot-search-checker@pair.app");
            registerAndLogin("slot-search-host@pair.app");
            accountsCreated = true;
        }
        searcher = userRepository.findByEmail("slot-search-checker@pair.app").orElseThrow();
        host = userRepository.findByEmail("slot-search-host@pair.app").orElseThrow();
    }

    @Test
    void search_duYogaDemainSoir_devraitRenvoyerLeCreneauCorrespondant() {
        stubIntent("demain soir");
        createSchedule(host, "yoga", tomorrowEvening(), PlaceType.PUBLIC, true, SlotStatus.OPEN, true, "Cours du soir");

        SearchResponse response = search(searcherToken, "du yoga demain soir");

        assertThat(response.type()).isEqualTo("results");
        SearchResultDto slotResult = firstSlot(response, "Cours du soir");

        assertThat(slotResult.startsAt()).isNotNull();
        assertThat(slotResult.organizerName()).isEqualTo(host.getDisplayName());
        assertThat(slotResult.enrolledCount()).isEqualTo(0);
    }

    @Test
    void search_neDoitJamaisRenvoyer_creneauFermeAuxPartenaires() {
        stubIntent(null);
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PUBLIC, false, SlotStatus.OPEN, true, "Cours fermé");

        SearchResponse response = search(searcherToken, "du yoga cette semaine");

        assertThat(anySlotTitled(response, "Cours fermé"))
            .as("un créneau fermé aux partenaires ne doit jamais apparaître").isFalse();
    }

    @Test
    void search_neDoitJamaisRenvoyer_creneauAppartenantAuCall() {
        stubIntent(null);
        // Le chercheur est ici lui-même l'hôte : doit être exclu comme /api/slots/feed
        // exclut l'appelant de son propre feed.
        createSchedule(searcher, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PUBLIC, true, SlotStatus.OPEN, true, "Mon propre cours");

        SearchResponse response = search(searcherToken, "du yoga cette semaine");

        assertThat(anySlotTitled(response, "Mon propre cours"))
            .as("le créneau de l'appelant lui-même ne doit jamais apparaître").isFalse();
    }

    @Test
    void search_creneauPriveSansAdresseExacte_neDoitJamaisExposerLatLng() {
        stubIntent(null);
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PRIVATE, true, SlotStatus.OPEN, false, "Cours privé");

        SearchResponse response = search(searcherToken, "du yoga");

        SearchResultDto slotResult = firstSlot(response, "Cours privé");
        assertThat(slotResult.lat()).isNull();
        assertThat(slotResult.lng()).isNull();
    }

    @Test
    void search_neDoitJamaisRenvoyer_creneauAnnuleOuPasse() {
        stubIntent(null);
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PUBLIC, true, SlotStatus.CANCELLED, true, "Cours annulé");
        createSchedule(host, "yoga", Instant.now().minus(1, ChronoUnit.HOURS),
            PlaceType.PUBLIC, true, SlotStatus.PAST, true, "Cours déjà passé");

        SearchResponse response = search(searcherToken, "du yoga cette semaine");

        assertThat(anySlotTitled(response, "Cours annulé")).isFalse();
        assertThat(anySlotTitled(response, "Cours déjà passé")).isFalse();
    }

    private void stubIntent(String timeHint) {
        when(embeddingService.isConfigured()).thenReturn(false);
        when(intentExtractor.extractIntent(any())).thenReturn(new SearchIntent(
            "yoga", "Sport", null, null, 5000, timeHint, false, null, "yoga"));
    }

    private Instant tomorrowEvening() {
        return Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(19, ChronoUnit.HOURS);
    }

    private SearchResultDto firstSlot(SearchResponse response, String expectedTitle) {
        return response.results().stream()
            .filter(r -> "slot".equals(r.resultType()) && expectedTitle.equals(r.title()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Aucun résultat slot '" + expectedTitle + "' dans : " + response.results()));
    }

    private boolean anySlotTitled(SearchResponse response, String title) {
        return response.results() != null && response.results().stream()
            .anyMatch(r -> "slot".equals(r.resultType()) && title.equals(r.title()));
    }

    private SearchResponse search(String token, String query) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, LAT, LNG, 20000))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private void createSchedule(User owner, String activitySlug, Instant startsAt,
                                 PlaceType placeType, boolean isOpenToPartners,
                                 SlotStatus status, boolean showExactAddress, String title) {
        Activity activity = activityRepository.findBySlug(activitySlug).orElseThrow();
        UserActivity userActivity = userActivityRepository.findByUserIdAndActivityId(owner.getId(), activity.getId())
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
            .placeName("Studio test recherche")
            .placeType(placeType)
            .addressPublic(placeType == PlaceType.ONLINE ? null : "1 rue de la Recherche")
            .showExactAddress(showExactAddress)
            .location(placeType == PlaceType.ONLINE ? null : geometryFactory.createPoint(new Coordinate(LNG, LAT)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
            .maxParticipants(8)
            .isOpenToPartners(isOpenToPartners)
            .status(status)
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
