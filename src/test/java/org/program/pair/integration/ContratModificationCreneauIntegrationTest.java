package org.program.pair.integration;

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
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Les trois questions de contrat de la demande mobile du 02/09 (module
 * {@code creneau-modifiable}), tranchées et verrouillées.
 *
 * <ul>
 *   <li>le {@code PUT} d'un créneau <b>fusionne</b> — et un corps qui renvoie
 *       toutes les valeurs à l'identique ne prévient personne ;</li>
 *   <li>{@code showExactAddress} se relit sur {@code ScheduleDto}, pour
 *       l'organisateur seul ;</li>
 *   <li>{@code isPubliclyShareable} est honoré à la création, où il était ignoré.</li>
 * </ul>
 */
class ContratModificationCreneauIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Brest, que personne d'autre n'emploie.
    private static final double LAT = 48.3904;
    private static final double LNG = -4.4861;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // — la sémantique du PUT —

    @Test
    void unChampAbsent_doitResterCeQuIlEtait() {
        Terrain terrain = terrain(PlaceType.PUBLIC, false);
        Schedule avant = scheduleRepository.findById(terrain.scheduleId()).orElseThrow();

        modifier(terrain, Map.of("welcomeNote", "Apportez de l'eau."));

        Schedule apres = scheduleRepository.findById(terrain.scheduleId()).orElseThrow();
        assertThat(apres.getWelcomeNote()).isEqualTo("Apportez de l'eau.");
        assertThat(apres.getPlaceName()).isEqualTo(avant.getPlaceName());
        assertThat(apres.getStartsAt()).isEqualTo(avant.getStartsAt());
        assertThat(apres.getEndsAt()).isEqualTo(avant.getEndsAt());
        assertThat(apres.getMaxParticipants()).isEqualTo(avant.getMaxParticipants());
        assertThat(apres.getAddressPublic()).isEqualTo(avant.getAddressPublic());
    }

    @Test
    void unFormulaireRenvoyeALIdentique_neDoitPrevenirPersonne() {
        // Le cas le plus fréquent d'un formulaire pré-rempli : ouvrir l'écran et
        // enregistrer sans rien toucher. L'app renvoie tous les champs.
        Terrain terrain = terrain(PlaceType.PUBLIC, false);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);
        Schedule s = scheduleRepository.findById(terrain.scheduleId()).orElseThrow();

        Map<String, Object> memeCorps = new HashMap<>();
        memeCorps.put("placeName", s.getPlaceName());
        memeCorps.put("placeType", s.getPlaceType().name());
        memeCorps.put("lat", s.getLocation().getY());
        memeCorps.put("lng", s.getLocation().getX());
        memeCorps.put("addressPublic", s.getAddressPublic());
        memeCorps.put("showExactAddress", s.getShowExactAddress());
        memeCorps.put("startsAt", s.getStartsAt().toString());
        memeCorps.put("endsAt", s.getEndsAt().toString());
        memeCorps.put("maxParticipants", s.getMaxParticipants());
        memeCorps.put("isOpenToPartners", s.getIsOpenToPartners());
        modifier(terrain, memeCorps);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
            .until(() -> notifications(inscrit) == 0L);
    }

    @Test
    void laSeuleAdresse_dUnDomicileDejaMontre_doitEtrePrise() {
        // Elle était ignorée sans erreur : l'adresse n'était enregistrée que si le
        // MÊME corps renvoyait showExactAddress=true. Un client qui fusionne
        // n'envoie que ce qui change.
        Terrain terrain = terrain(PlaceType.PRIVATE, true);

        modifier(terrain, Map.of("addressPublic", "4 rue de Siam, Brest"));

        assertThat(scheduleRepository.findById(terrain.scheduleId()).orElseThrow().getAddressPublic())
            .isEqualTo("4 rue de Siam, Brest");
    }

    // — showExactAddress en lecture —

    @Test
    void showExactAddress_doitSeRelire_parLOrganisateurSeul() {
        Terrain terrain = terrain(PlaceType.PRIVATE, true);
        Compte lecteur = compte();

        webTestClient.get().uri("/api/programs/{id}", terrain.programId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.schedules[0].showExactAddress").isEqualTo(true);

        webTestClient.get().uri("/api/programs/{id}", terrain.programId())
            .headers(h -> h.setBearerAuth(lecteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.schedules[0].showExactAddress").doesNotExist();
    }

    @Test
    void showExactAddress_doitValoirFaux_pourUnDomicileMasque() {
        Terrain terrain = terrain(PlaceType.PRIVATE, false);

        webTestClient.get().uri("/api/programs/{id}", terrain.programId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.schedules[0].showExactAddress").isEqualTo(false);
    }

    // — isPubliclyShareable à la création —

    @Test
    void isPubliclyShareable_doitEtreHonore_aLaCreation() {
        Terrain terrain = terrain(PlaceType.PUBLIC, false);

        UUID ferme = creer(terrain, false);
        UUID parDefaut = creer(terrain, null);

        assertThat(scheduleRepository.findById(ferme).orElseThrow().getIsPubliclyShareable()).isFalse();
        assertThat(scheduleRepository.findById(parDefaut).orElseThrow().getIsPubliclyShareable()).isTrue();
    }

    @Test
    void isPubliclyShareable_doitEtreIgnore_parLePut() {
        // PATCH /slots/{id}/shareable est le seul chemin après la création : un
        // formulaire de modification qui la renverrait à l'aveugle rallumerait un
        // partage coupé depuis la route dédiée.
        Terrain terrain = terrain(PlaceType.PUBLIC, false);
        jdbcTemplate.update("UPDATE schedules SET is_publicly_shareable = FALSE WHERE id = ?",
            terrain.scheduleId());

        modifier(terrain, Map.of("isPubliclyShareable", true, "welcomeNote", "Bienvenue."));

        assertThat(scheduleRepository.findById(terrain.scheduleId()).orElseThrow().getIsPubliclyShareable())
            .isFalse();
    }

    // — outils —

    private record Compte(UUID id, String token) {}

    private record Terrain(Compte hote, UUID programId, UUID scheduleId) {}

    private Terrain terrain(PlaceType type, boolean adresseVisible) {
        Compte hote = compte();
        User host = userRepository.findById(hote.id()).orElseThrow();
        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Contrat " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .allowParticipantMessages(true)
            .build());

        Instant debut = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle " + UUID.randomUUID().toString().substring(0, 6))
            .placeType(type)
            .addressPublic("12 rue Jean-Jaurès, Brest")
            .showExactAddress(adresseVisible)
            .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
            .startsAt(debut)
            .endsAt(debut.plus(Duration.ofHours(2)))
            .maxParticipants(5)
            .isOpenToPartners(true)
            .build());

        return new Terrain(hote, program.getId(), schedule.getId());
    }

    private UUID creer(Terrain terrain, Boolean partageable) {
        Instant debut = Instant.now().plus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> corps = new HashMap<>();
        corps.put("placeName", "Plage du Moulin Blanc");
        corps.put("placeType", "PUBLIC");
        corps.put("lat", LAT);
        corps.put("lng", LNG);
        corps.put("addressPublic", "Moulin Blanc, Brest");
        corps.put("startsAt", debut.toString());
        corps.put("endsAt", debut.plus(Duration.ofHours(2)).toString());
        if (partageable != null) {
            corps.put("isPubliclyShareable", partageable);
        }
        String id = webTestClient.post().uri("/api/programs/{id}/schedules", terrain.programId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody()
            .get("id").toString();
        return UUID.fromString(id);
    }

    private void modifier(Terrain terrain, Map<String, Object> champs) {
        webTestClient.put()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                terrain.programId(), terrain.scheduleId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new HashMap<>(champs))
            .exchange().expectStatus().isOk();
    }

    private void rejoindre(Compte qui, Terrain terrain) {
        webTestClient.post().uri("/api/slots/{id}/join", terrain.scheduleId())
            .headers(h -> h.setBearerAuth(qui.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private long notifications(Compte qui) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            Long.class, qui.id(), NotificationType.SCHEDULE_CHANGED.name());
        return n == null ? 0L : n;
    }

    private Compte compte() {
        String email = uniqueEmail("contrat-creneau");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Contrat" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();
        adresseVerifiee(email);
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return new Compte(userRepository.findByEmail(email).orElseThrow().getId(), auth.accessToken());
    }
}
