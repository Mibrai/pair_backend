package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande 1 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md : GET
 * /api/activities/browse, la jointure activité × programmes faite côté serveur,
 * sur la maille UserActivity arbitrée avec le client.
 *
 * <p>Un test par critère d'acceptation du §1 du prompt.
 *
 * <p>Les organisateurs sont créés directement en base : ils n'ont jamais besoin
 * de jeton, seul l'appelant en a un. Cela tient la classe à <b>une</b>
 * inscription, alors que le plafond est de 5/heure/IP pour toute la suite.
 *
 * <p>Fixtures posées à 40°S / 70°O, hors de toute zone peuplée par les seeds et
 * hors des zones utilisées par les autres classes, pour que les assertions
 * portent sur des ensembles exacts.
 */
class ActivityBrowseIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final double LAT = -40.0;
    private static final double LNG = -70.0;
    /** ~60 km au sud : hors d'un rayon de 25 km, dedans pour 100 km. */
    private static final double FAR_LAT = -40.539;

    private static final String CALLER_EMAIL = "browse-caller@pair.app";
    private static boolean fixturesCreated = false;
    private static String token;

    @BeforeEach
    void setUp() {
        // Deux gardes séparés : si la création des fixtures échoue, on ne
        // rejoue pas l'inscription — le plafond est de 5/heure/IP pour toute
        // la suite, et le rejouer épuiserait le budget des autres classes.
        if (token == null) {
            token = registerAndLogin(CALLER_EMAIL);
        }
        if (!fixturesCreated) {
            createFixtures();
            fixturesCreated = true;
        }
    }

    @Test
    void deuxOrganisateursDeLaMemeActivite_doiventDonnerDeuxEntreesDistinctes() {
        List<JsonNode> entries = nearbyEntries(100_000);

        List<JsonNode> yoga = entries.stream()
            .filter(e -> "Yoga".equals(e.get("activityName").asText()))
            .toList();

        assertThat(yoga)
            .as("le nom d'activité n'est plus la clé de jointure")
            .hasSize(2);
        assertThat(yoga).extracting(e -> e.get("activityId").asText())
            .as("même activité du référentiel")
            .containsOnly(yoga.get(0).get("activityId").asText());
        assertThat(yoga).extracting(e -> e.get("userActivityId").asText())
            .doesNotHaveDuplicates();
        assertThat(yoga).extracting(e -> e.get("organizerId").asText())
            .doesNotHaveDuplicates();
        // Chacune garde ses propres programmes.
        assertThat(yoga).extracting(e -> e.get("programCount").asInt())
            .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void uneEntreeSansAucunProgramme_doitQuandMemePorterSonOrganisateur() {
        JsonNode entry = entryNamed(nearbyEntries(100_000), "Escalade");

        assertThat(entry.get("organizerId").isNull())
            .as("un auteur non cliquable était le défaut structurel de l'ancienne jointure")
            .isFalse();
        assertThat(entry.get("organizerName").asText()).isNotBlank();
        assertThat(entry.get("programCount").asInt()).isZero();
    }

    @Test
    void radiusMeters_doitEtreReellementApplique() {
        assertThat(nearbyEntries(25_000))
            .extracting(e -> e.get("activityName").asText())
            .as("l'activité à 60 km ne doit pas apparaître pour radiusMeters=25000")
            .doesNotContain("Natation");

        assertThat(nearbyEntries(100_000))
            .extracting(e -> e.get("activityName").asText())
            .contains("Natation");
    }

    @Test
    void uneActiviteEnLigne_doitEtreRendueSansPosition_etNonFiltreeParLeRayon() {
        // Critère du prompt : sans coordonnées, lat/lng/distanceMeters à null —
        // et surtout, pas écartée par le rayon.
        JsonNode entry = entryNamed(nearbyEntries(1_000), "Meditation");

        assertThat(entry.get("lat").isNull()).isTrue();
        assertThat(entry.get("lng").isNull()).isTrue();
        assertThat(entry.get("distanceMeters").isNull()).isTrue();
    }

    @Test
    void lesEntreesLocalisees_doiventPrecederCellesSansPosition() {
        List<JsonNode> entries = nearbyEntries(100_000);

        int lastLocated = -1;
        int firstUnlocated = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).get("lat").isNull()) {
                firstUnlocated = Math.min(firstUnlocated, i);
            } else {
                lastLocated = Math.max(lastLocated, i);
            }
        }
        assertThat(lastLocated)
            .as("sinon les premières pages seraient noyées par des entrées sans lieu")
            .isLessThan(firstUnlocated);
    }

    @Test
    void deuxPagesSuccessives_neDoiventNiSeRecouvrirNiPerdreUneEntree() {
        List<String> page0 = idsOf(browse(b -> query(b, 100_000).queryParam("page", 0).queryParam("size", 2).build()));
        List<String> page1 = idsOf(browse(b -> query(b, 100_000).queryParam("page", 1).queryParam("size", 2).build()));
        List<String> whole = idsOf(browse(b -> query(b, 100_000).queryParam("size", 100).build()));

        assertThat(page0).hasSize(2);
        assertThat(page0).doesNotContainAnyElementsOf(page1);
        assertThat(whole.subList(0, 4))
            .as("la concaténation des pages doit reproduire l'ordre total")
            .containsExactlyElementsOf(concat(page0, page1));
    }

    @Test
    void totalElements_doitCompterLaZone_pasLaPage() {
        JsonNode small = browse(b -> query(b, 100_000).queryParam("size", 2).build());
        JsonNode large = browse(b -> query(b, 100_000).queryParam("size", 100).build());

        int total = small.get("page").get("totalElements").asInt();
        assertThat(small.get("content")).hasSize(2);
        assertThat(total).isGreaterThan(2);
        assertThat(total)
            .as("le total ne dépend pas de la taille de page demandée")
            .isEqualTo(large.get("page").get("totalElements").asInt());
    }

    @Test
    void sansIncludePrograms_laRouteResteUtilisable_etNEmbarquePasLesProgrammes() {
        JsonNode entry = entryNamed(nearbyEntries(100_000), "Natation");

        assertThat(entry.get("programs") == null || entry.get("programs").isNull()).isTrue();
        assertThat(entry.get("programCount").asInt()).isPositive();
    }

    @Test
    void avecIncludePrograms_lesProgrammesSontJointsEtBornes() {
        JsonNode response = browse(b -> query(b, 100_000)
            .queryParam("includePrograms", true).queryParam("size", 100).build());

        JsonNode entry = entryNamed(contentOf(response), "Natation");
        assertThat(entry.get("programs").isArray()).isTrue();
        assertThat(entry.get("programs")).isNotEmpty();
        assertThat(entry.get("programs").size())
            .as("borné aux 3 prochains, pas toute la liste")
            .isLessThanOrEqualTo(3);
        assertThat(entry.get("programs").get(0).get("title").asText()).isNotBlank();
    }

    @Test
    void uneEntreeExpiree_estEcarteeParDefaut_etRendueSurDemande() {
        assertThat(nearbyEntries(100_000))
            .extracting(e -> e.get("activityName").asText())
            .doesNotContain("Judo");

        JsonNode withExpired = browse(b -> query(b, 100_000)
            .queryParam("includeExpired", true).queryParam("size", 100).build());

        JsonNode judo = entryNamed(contentOf(withExpired), "Judo");
        assertThat(judo.get("isExpired").asBoolean()).isTrue();
        assertThat(judo.get("nextSessionAt").isNull())
            .as("isExpired implique nextSessionAt null, sans exception")
            .isTrue();
    }

    @Test
    void uneEntreeSansCreneau_nEstJamaisExpiree() {
        JsonNode entry = entryNamed(nearbyEntries(100_000), "Escalade");
        assertThat(entry.get("isExpired").asBoolean()).isFalse();
        assertThat(entry.get("nextSessionAt").isNull()).isTrue();
    }

    @Test
    void unRayonHorsBornes_doitEtreRefuse() {
        webTestClient.get()
            .uri(b -> query(b, 500_000).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MAP_RADIUS_OUT_OF_RANGE");
    }

    // — lot D8 : les filtres personnels et leurs compteurs —

    @Test
    void mesActivites_doitRendreCeQuiSePratiqueDansMesSports_pasMesPropresAnnonces() {
        // La sémantique retenue, et celle que le test verrouille : l'appelant
        // déclare « Yoga » sans le rendre visible sur la carte, et le filtre lui
        // rend les entrées de Lena et de Marc — pas la sienne, qui n'est pas
        // publiée. « Mes activités » désigne ce qui se pratique autour de moi
        // dans mes sports, pas la liste de ce que j'annonce.
        //
        // Les assertions portent sur l'inclusion d'un côté et l'exclusion de
        // l'autre, jamais sur un ensemble exact : d'autres méthodes de cette
        // classe déclarent d'autres activités pour le même appelant, et JUnit ne
        // garantit pas leur ordre.
        declareForCaller("Yoga", false);

        List<String> names = contentOf(browse(b -> query(b, 100_000)
            .queryParam("size", 100)
            .queryParam("myActivitiesOnly", true)
            .build()))
            .stream().map(e -> e.get("activityName").asText()).toList();

        assertThat(names).contains("Yoga");
        assertThat(names.stream().filter("Yoga"::equals).count())
            .as("les deux organisateurs de Yoga, pas un seul")
            .isEqualTo(2);
        assertThat(names)
            .as("Meditation n'est déclarée par personne du côté de l'appelant")
            .doesNotContain("Meditation");
    }

    @Test
    void mesAbonnements_doitNeRendreQueCeQueJeSuis() {
        List<JsonNode> all = nearbyEntries(100_000);
        String followed = entryNamed(all, "Escalade").get("userActivityId").asText();
        subscribeToEntry(followed);

        List<JsonNode> entries = contentOf(browse(b -> query(b, 100_000)
            .queryParam("size", 100)
            .queryParam("subscribedOnly", true)
            .build()));

        assertThat(idsOf(browse(b -> query(b, 100_000)
            .queryParam("size", 100)
            .queryParam("subscribedOnly", true)
            .build()))).containsExactly(followed);
        assertThat(entries).allMatch(e -> e.get("subscribed").asBoolean());
    }

    @Test
    void lesDeuxFiltres_doiventSeCumulerParEt() {
        // Deux entrées de la même activité, une seule suivie : le cumul doit
        // rendre celle-là et pas l'autre. C'est ce qui distingue un ET d'un OU,
        // et l'assertion tient quel que soit l'ordre des méthodes.
        declareForCaller("Yoga", false);

        List<JsonNode> allYoga = nearbyEntries(100_000).stream()
            .filter(e -> "Yoga".equals(e.get("activityName").asText()))
            .toList();
        assertThat(allYoga).hasSize(2);

        String followed = allYoga.get(0).get("userActivityId").asText();
        String notFollowed = allYoga.get(1).get("userActivityId").asText();
        subscribeToEntry(followed);

        List<String> ids = idsOf(browse(b -> query(b, 100_000)
            .queryParam("size", 100)
            .queryParam("myActivitiesOnly", true)
            .queryParam("subscribedOnly", true)
            .build()));

        assertThat(ids).contains(followed);
        assertThat(ids)
            .as("l'autre entrée est bien « une de mes activités », mais n'est pas suivie")
            .doesNotContain(notFollowed);
    }

    @Test
    void sansFiltrePersonnel_laListeNeDoitPasChanger() {
        // Non-régression : les deux paramètres sont absents dans le client publié.
        assertThat(nearbyEntries(100_000)).isNotEmpty();
    }

    @Test
    void lesCompteurs_doiventAnnoncerCeQuUneCaseRendrait_pasCeQuOnADeja() {
        // Le point de conception des facettes : elles ignorent les filtres de
        // même nature. Demander les compteurs AVEC myActivitiesOnly ne doit pas
        // faire retomber le total sur le sous-ensemble — sinon toutes les cases
        // non cochées afficheraient zéro et passeraient pour des impasses.
        declareForCaller("Escalade", false);

        JsonNode wide = facets(b -> b.path("/api/activities/browse/facets")
            .queryParam("lat", LAT).queryParam("lng", LNG)
            .queryParam("radiusMeters", 100_000).build());
        JsonNode filtered = facets(b -> b.path("/api/activities/browse/facets")
            .queryParam("lat", LAT).queryParam("lng", LNG)
            .queryParam("radiusMeters", 100_000)
            .queryParam("myActivitiesOnly", true).build());

        assertThat(wide.get("total").asLong()).isPositive();
        assertThat(filtered.get("total").asLong()).isEqualTo(wide.get("total").asLong());
        assertThat(wide.get("myActivities").asLong()).isPositive();
    }

    @Test
    void lesCompteurs_doiventVentilerParNiveau_sansInventerDeDeclaration() {
        // Les fixtures ne déclarent aucun niveau : la clé UNSPECIFIED les porte,
        // et les ranger sous « ANY » aurait inventé une déclaration.
        JsonNode facets = facets(b -> b.path("/api/activities/browse/facets")
            .queryParam("lat", LAT).queryParam("lng", LNG)
            .queryParam("radiusMeters", 100_000).build());

        assertThat(facets.get("byLevel")).isNotNull();
        long sum = 0;
        for (java.util.Iterator<String> it = facets.get("byLevel").fieldNames(); it.hasNext(); ) {
            sum += facets.get("byLevel").get(it.next()).asLong();
        }
        assertThat(sum)
            .as("la somme des niveaux doit retomber sur le total")
            .isEqualTo(facets.get("total").asLong());
    }

    // — helpers —

    /** Déclare une activité pour l'appelant lui-même. */
    private void declareForCaller(String activityName, boolean visibleOnMap) {
        User caller = userRepository.findByEmail(CALLER_EMAIL).orElseThrow();
        Activity activity = activityRepository.findBySlug(slugOf(activityName)).orElseThrow();
        if (userActivityRepository.findByUserIdAndActivityId(caller.getId(), activity.getId()).isEmpty()) {
            userActivityRepository.save(UserActivity.builder()
                .user(caller).activity(activity).visibleOnMap(visibleOnMap).build());
        }
    }

    private void subscribeToEntry(String userActivityId) {
        webTestClient.post().uri("/api/user-activities/{id}/subscription", userActivityId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private JsonNode facets(Function<UriBuilder, URI> uri) {
        byte[] body = webTestClient.get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody().returnResult().getResponseBody();
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Réponse illisible", e);
        }
    }


    private UriBuilder query(UriBuilder b, int radiusMeters) {
        return b.path("/api/activities/browse")
            .queryParam("lat", LAT)
            .queryParam("lng", LNG)
            .queryParam("radiusMeters", radiusMeters);
    }

    private List<JsonNode> nearbyEntries(int radiusMeters) {
        return contentOf(browse(b -> query(b, radiusMeters).queryParam("size", 100).build()));
    }

    private List<JsonNode> contentOf(JsonNode response) {
        return java.util.stream.StreamSupport
            .stream(response.get("content").spliterator(), false)
            .toList();
    }

    private JsonNode entryNamed(List<JsonNode> entries, String activityName) {
        return entries.stream()
            .filter(e -> activityName.equals(e.get("activityName").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Aucune entrée '" + activityName + "' dans "
                + entries.stream().map(e -> e.get("activityName").asText()).toList()));
    }

    private List<String> idsOf(JsonNode response) {
        return contentOf(response).stream().map(e -> e.get("userActivityId").asText()).toList();
    }

    private List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private JsonNode browse(Function<UriBuilder, URI> uri) {
        byte[] body = webTestClient.get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Réponse illisible", e);
        }
    }

    // — fixtures —

    private void createFixtures() {
        User lena = organizer("browse-lena@pair.app", "Lena Müller");
        User marc = organizer("browse-marc@pair.app", "Marc Dubois");

        // Deux « Yoga », deux organisateurs : le cas que la jointure par nom
        // fusionnait en une seule carte.
        UserActivity lenaYoga = declare(lena, "Yoga");
        program(lenaYoga, "Yoga Lena 1", LAT, LNG, future());
        program(lenaYoga, "Yoga Lena 2", LAT, LNG, future());

        UserActivity marcYoga = declare(marc, "Yoga");
        program(marcYoga, "Yoga Marc", LAT, LNG, future());

        // Sans aucun programme : doit exister, et porter son organisateur.
        declare(lena, "Escalade");

        // À 60 km : présente à 100 km, absente à 25 km.
        UserActivity marcNatation = declare(marc, "Natation");
        program(marcNatation, "Natation Marc", FAR_LAT, LNG, future());
        program(marcNatation, "Natation Marc 2", FAR_LAT, LNG, future());

        // En ligne : un programme, aucun créneau localisé.
        programWithoutLocation(declare(lena, "Meditation"), "Méditation en ligne");

        // Datée mais sans séance à venir : expirée.
        program(declare(marc, "Judo"), "Judo passé", LAT, LNG,
            Instant.now().minus(2, ChronoUnit.DAYS));
    }

    private User organizer(String email, String displayName) {
        return userRepository.save(User.builder()
            .email(email)
            .passwordHash("$2a$10$notusedbecausethisuserneverlogsin000000000000000000000")
            .displayName(displayName)
            .isActive(true)
            .locationPublic(false)
            .build());
    }

    /**
     * Les activités sont créées par le test, pas cherchées dans les seeds : leur
     * contenu varie d'une migration à l'autre, et les assertions portent ici sur
     * des ensembles exacts. La catégorie est empruntée à une activité existante,
     * la contrainte de clé étrangère l'exigeant.
     */
    private UserActivity declare(User owner, String activityName) {
        Activity activity = activityRepository.findBySlug(slugOf(activityName))
            .orElseGet(() -> activityRepository.save(Activity.builder()
                .name(activityName)
                .slug(slugOf(activityName))
                .description(activityName + " — fixture de test")
                .icon("sports")
                .category(activityRepository.findAll().get(0).getCategory())
                .build()));
        return userActivityRepository.save(
            UserActivity.builder().user(owner).activity(activity).visibleOnMap(true).build());
    }

    private String slugOf(String activityName) {
        return "browse-fixture-" + activityName.toLowerCase();
    }

    private void program(UserActivity userActivity, String title, double lat, double lng, Instant startsAt) {
        Program program = saveProgram(userActivity, title, startsAt);
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName(title)
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue de l'Explorer")
            .showExactAddress(true)
            .location(geometryFactory.createPoint(new Coordinate(lng, lat)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
            .maxParticipants(8)
            .isOpenToPartners(true)
            .status(SlotStatus.OPEN)
            .build());
    }

    private void programWithoutLocation(UserActivity userActivity, String title) {
        saveProgram(userActivity, title, null);
    }

    private Program saveProgram(UserActivity userActivity, String title, Instant nextSessionAt) {
        return programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .nextSessionAt(nextSessionAt != null && nextSessionAt.isAfter(Instant.now()) ? nextSessionAt : null)
            .build());
    }

    private Instant future() {
        return Instant.now().plus(3, ChronoUnit.DAYS);
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
