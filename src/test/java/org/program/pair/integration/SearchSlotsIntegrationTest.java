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
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * POST /api/search doit désormais renvoyer des créneaux (resultType="slot"),
 * pas seulement des personnes/programmes — voir docs/specs/BACKEND_SEARCH_SLOTS.md.
 * RuleBasedIntentExtractor tourne réellement (déterministe) ; seul
 * LocalEmbeddingService est mocké (vecteur nul, comme SemanticSearchIntegrationTest).
 *
 * N'enregistre que 2 comptes pour toute la classe (une fois, via un garde
 * statique) : l'inscription est limitée à 5/heure/IP (RateLimiter), un
 * budget vite épuisé si chaque test enregistre les siens. Note : pas de
 * @TestInstance(PER_CLASS)/@BeforeAll ici — ça casse l'ordre d'initialisation
 * de @Testcontainers (le port du conteneur n'est pas encore mappé quand
 * Spring construit le contexte).
 */
class SearchSlotsIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    /** La même zone que TimeHintParser : c'est lui qui décide de ce qu'est « demain ». */
    private static final ZoneId SEARCH_ZONE = ZoneId.of("Europe/Paris");

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
        forceFulltextFallback();
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
        forceFulltextFallback();
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PUBLIC, false, SlotStatus.OPEN, true, "Cours fermé");

        SearchResponse response = search(searcherToken, "du yoga cette semaine");

        assertThat(anySlotTitled(response, "Cours fermé"))
            .as("un créneau fermé aux partenaires ne doit jamais apparaître").isFalse();
    }

    @Test
    void search_neDoitJamaisRenvoyer_creneauAppartenantAuCall() {
        forceFulltextFallback();
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
        forceFulltextFallback();
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PRIVATE, true, SlotStatus.OPEN, false, "Cours privé");

        SearchResponse response = search(searcherToken, "du yoga");

        SearchResultDto slotResult = firstSlot(response, "Cours privé");
        assertThat(slotResult.lat()).isNull();
        assertThat(slotResult.lng()).isNull();
    }

    @Test
    void search_neDoitJamaisRenvoyer_creneauAnnuleOuPasse() {
        forceFulltextFallback();
        createSchedule(host, "yoga", Instant.now().plus(2, ChronoUnit.DAYS),
            PlaceType.PUBLIC, true, SlotStatus.CANCELLED, true, "Cours annulé");
        createSchedule(host, "yoga", Instant.now().minus(1, ChronoUnit.HOURS),
            PlaceType.PUBLIC, true, SlotStatus.PAST, true, "Cours déjà passé");

        SearchResponse response = search(searcherToken, "du yoga cette semaine");

        assertThat(anySlotTitled(response, "Cours annulé")).isFalse();
        assertThat(anySlotTitled(response, "Cours déjà passé")).isFalse();
    }

    // RuleBasedIntentExtractor tourne réellement et résout "yoga" via la taxonomie
    // à partir de la requête brute (canonicalActivitySlug + timeHint dérivés du
    // texte, sans mock) ; seul l'embedding est forcé à un vecteur nul pour isoler
    // le chemin plein texte / créneaux.
    private void forceFulltextFallback() {
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
    }

    /**
     * Demain 19 h <b>à Paris</b>, et non « dans 24 h arrondi en UTC ».
     *
     * <p>La version précédente enchaînait {@code plus(1, DAYS)} et
     * {@code truncatedTo(DAYS)}, qui tronque en UTC, alors que
     * {@link org.program.pair.domain.search.TimeHintParser} résout « demain »
     * dans {@code Europe/Paris}. Les deux notions divergent d'un jour entre
     * minuit et 2 h du matin, heure d'été : le test posait son créneau le 12
     * quand le serveur cherchait le 13, et échouait deux heures par nuit sans
     * qu'aucun code de production ne soit en cause.
     */
    private Instant tomorrowEvening() {
        return LocalDate.now(SEARCH_ZONE).plusDays(1).atTime(19, 0).atZone(SEARCH_ZONE).toInstant();
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
