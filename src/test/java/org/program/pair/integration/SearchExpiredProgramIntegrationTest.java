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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * {@code POST /api/search} — de quoi reconnaître un programme terminé.
 *
 * <p>Le rapport client du 05/09 : la recherche <b>rendait déjà</b> les
 * programmes dont toutes les séances sont passées — 25 sur 25 autour de Paris —
 * et c'est très bien, on veut pouvoir retrouver un programme terminé. Ce qui
 * manquait, c'était de quoi le <b>distinguer</b> d'un programme vivant :
 * {@code startsAt} et {@code endsAt} valent {@code null} sur un résultat de type
 * {@code program}, et aucun autre champ ne disait le temps. Trois contournements
 * client avaient été mesurés, aucun ne tenait.
 *
 * <p>Ce fichier verrouille les deux champs et, surtout, <b>l'accord de la
 * définition d'une route à l'autre</b> : ce que ces tests affirment ici est ce
 * que {@code GET /activities/browse} affirme déjà, au mot près — daté et sans
 * occurrence future, la séance en cours ne comptant pas comme passée.
 *
 * <p>Le vecteur d'embedding est neutralisé, comme dans les autres tests de
 * recherche : le chemin exercé est celui des requêtes SQL, celui des quatre
 * couches de rappel qui rendent la grande majorité des résultats en production.
 * Le mapping d'entités du rappel vectoriel, l'autre producteur, est couvert par
 * {@code SemanticSearchServiceTest}.
 */
class SearchExpiredProgramIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // Une zone déserte du Groenland oriental, comme les tests de la carte : la
    // base de test est semée de programmes un peu partout en Europe, et les
    // assertions de ce fichier portent sur des titres uniques plutôt que sur des
    // comptes — sauf celle du filtre, qui compare deux appels au même endroit.
    private static final double LAT = 70.5, LNG = -22.0;

    private static final String HOST_EMAIL = "search-expired-host@pair.app";
    private static final String SEARCHER_EMAIL = "search-expired-searcher@pair.app";

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
        doReturn(new float[384]).when(embeddingService).generateEmbedding(any());
    }

    /** La demande du client, littéralement. */
    @Test
    void unProgrammeDontToutesLesSeancesSontPassees_doitPorterIsExpired() {
        Instant fini = Instant.now().minus(3, ChronoUnit.DAYS);
        String title = createProgram(fini, fini.plus(1, ChronoUnit.HOURS), SlotStatus.PAST);

        SearchResultDto result = findByTitle(title);

        assertThat(result.isExpired()).isTrue();
        assertThat(result.nextSessionAt())
            .as("expiré implique nextSessionAt nul, sans exception")
            .isNull();
    }

    @Test
    void unProgrammeAvecUneSeanceAVenir_doitPorterSaProchaineSeance() {
        Instant demain = Instant.now().plus(1, ChronoUnit.DAYS);
        String title = createProgram(demain, demain.plus(1, ChronoUnit.HOURS), SlotStatus.OPEN);

        SearchResultDto result = findByTitle(title);

        assertThat(result.isExpired()).isFalse();
        assertThat(result.nextSessionAt()).isCloseTo(demain, within(2, ChronoUnit.SECONDS));
    }

    /**
     * La règle que le client a citée en toutes lettres, et celle qui coûte le
     * plus cher à enfreindre : « un programme qu'on vient de créer et dont
     * l'horaire n'est pas encore posé reste vivant — le griser punirait son
     * auteur pour une étape qu'il n'a pas faite ».
     */
    @Test
    void unProgrammeSansAucuneSeance_neDoitJamaisEtreDitExpire() {
        String title = createProgramWithoutSchedule();

        SearchResultDto result = findByTitle(title);

        assertThat(result.isExpired()).isFalse();
        assertThat(result.nextSessionAt()).isNull();
    }

    /**
     * <b>Le test le plus important du fichier.</b> « Terminé » se mesure sur la
     * fin, jamais sur le début. Une séance commencée il y a une demi-heure et
     * qui dure encore ne rend pas son programme expiré — sans quoi il se
     * griserait, quitterait la liste et perdrait son bouton « Rejoindre » à la
     * seconde où le cours commence, c'est-à-dire à la minute où il prouve qu'il
     * est vivant.
     */
    @Test
    void uneSeanceEnCours_neDoitPasRendreLeProgrammeExpire() {
        Instant debut = Instant.now().minus(30, ChronoUnit.MINUTES);
        String title = createProgram(debut, debut.plus(2, ChronoUnit.HOURS), SlotStatus.OPEN);

        SearchResultDto result = findByTitle(title);

        assertThat(result.isExpired()).isFalse();
        assertThat(result.nextSessionAt()).isCloseTo(debut, within(2, ChronoUnit.SECONDS));
    }

    /**
     * Sans fin déclarée, la convention du dépôt s'applique : deux heures, lues
     * sur {@code SlotTiming} et interpolées dans le SQL depuis là. Un créneau
     * commencé il y a trois heures est donc fini, même si {@code ends_at} est nul
     * en base — c'est le cas le plus courant en production.
     */
    @Test
    void sansFinDeclaree_laConventionDeDeuxHeuresSApplique() {
        String encoreEnCours = createProgram(
            Instant.now().minus(1, ChronoUnit.HOURS), null, SlotStatus.OPEN);
        String termine = createProgram(
            Instant.now().minus(3, ChronoUnit.HOURS), null, SlotStatus.PAST);

        assertThat(findByTitle(encoreEnCours).isExpired()).isFalse();
        assertThat(findByTitle(termine).isExpired()).isTrue();
    }

    /**
     * Le bonus de la demande 1 : {@code includeExpired}.
     *
     * <p><b>Son défaut vaut {@code true}</b>, contrairement à celui de
     * {@code GET /activities/browse}. Ce n'est pas une inattention : chaque route
     * reconduit par défaut ce qu'elle faisait la veille — {@code browse}
     * écartait déjà les entrées expirées, {@code /search} les rendait déjà, et le
     * client a demandé qu'elle continue de le faire. Un défaut à {@code false}
     * aurait vidé, le jour du déploiement, l'interrupteur « Afficher ce qui est
     * terminé » de la version publiée : le champ serait apparu, l'interrupteur se
     * serait levé, et il n'aurait rien eu à montrer.
     */
    @Test
    void includeExpired_doitEcarterLesTerminesSansToucherAuxVivants() {
        Instant fini = Instant.now().minus(3, ChronoUnit.DAYS);
        String termine = createProgram(fini, fini.plus(1, ChronoUnit.HOURS), SlotStatus.PAST);
        Instant demain = Instant.now().plus(1, ChronoUnit.DAYS);
        String vivant = createProgram(demain, demain.plus(1, ChronoUnit.HOURS), SlotStatus.OPEN);

        SearchResponse avec = search(null);
        assertThat(titles(avec)).contains(termine, vivant);

        SearchResponse sans = search(false);
        assertThat(titles(sans))
            .as("le terminé sort, le vivant reste")
            .doesNotContain(termine)
            .contains(vivant);

        // Et le compte suit la liste : le filtre porte avant la découpe, donc
        // totalCount n'annonce pas des résultats que la page n'a plus.
        assertThat(sans.totalCount()).isLessThan(avec.totalCount());
        assertThat(sans.results()).allSatisfy(r -> assertThat(r.isExpired()).isFalse());
    }

    /** Le contre-test : envoyer explicitement {@code true} ne change rien au défaut. */
    @Test
    void includeExpiredVrai_doitEtreLeMemeQueLeDefaut() {
        Instant fini = Instant.now().minus(3, ChronoUnit.DAYS);
        String termine = createProgram(fini, fini.plus(1, ChronoUnit.HOURS), SlotStatus.PAST);

        assertThat(titles(search(true))).contains(termine);
        assertThat(titles(search(null))).contains(termine);
    }

    // — fixtures —

    private String createProgram(Instant startsAt, Instant endsAt, SlotStatus status) {
        String title = "Yoga passé " + UUID.randomUUID();
        Program program = saveProgram(title);
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio du test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Passé")
            .showExactAddress(true)
            .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
            .startsAt(startsAt)
            .endsAt(endsAt)
            .maxParticipants(8)
            .isOpenToPartners(true)
            .status(status)
            .build());
        return title;
    }

    private String createProgramWithoutSchedule() {
        String title = "Yoga sans date " + UUID.randomUUID();
        saveProgram(title);
        return title;
    }

    private Program saveProgram(String title) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository
            .findByUserIdAndActivityId(host.getId(), yoga.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(host).activity(yoga).build()));

        return programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    // — appels —

    private SearchResultDto findByTitle(String title) {
        return search(null).results().stream()
            .filter(r -> "program".equals(r.resultType()) && title.equals(r.title()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Programme '" + title + "' absent des résultats"));
    }

    private List<String> titles(SearchResponse response) {
        return response.results() == null
            ? List.of()
            : response.results().stream().map(SearchResultDto::title).toList();
    }

    private SearchResponse search(Boolean includeExpired) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(h -> h.setBearerAuth(searcherToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest("yoga", LAT, LNG, 50_000, null, 100, null, includeExpired))
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
