package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
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
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Avis de programme sans note, et lus par l'organisateur seul — étapes A et C du
 * module {@code avis} (P-BL-10, décision D4), calées sur la réponse de l'app du 14/09.
 *
 * <ul>
 *   <li><b>A</b> : {@code score} n'est plus exigé à l'écriture ;</li>
 *   <li><b>C</b> : aucune note n'est enregistrée ni rendue, anciennes comprises ;
 *       le commentaire n'est lu que par l'organisateur et par l'auteur de l'avis.
 *       Tout autre lecteur reçoit une page vide en {@code 200}, jamais un
 *       {@code 403}, que les versions 16 et 17 afficheraient comme une panne.</li>
 * </ul>
 */
class AvisSansNoteIntegrationTest extends AbstractIntegrationTest {

    private static final String COMMENTAIRE =
        "Séance très bien menée, ambiance accueillante et rythme adapté à tous.";

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // — étape A : l'écriture —

    @Test
    void unAvisSansNote_doitEtreAccepte() {
        Decor d = decor("sans-note");

        poster(d.auteur(), Map.of("programId", d.programId().toString(), "comment", COMMENTAIRE))
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.score").doesNotExist();

        assertThat(scoreEnBase(UUID.fromString(idDeLAvis(d)))).isNull();
    }

    @Test
    void uneNoteEncoreEnvoyee_doitEtreAcceptee_maisPasEnregistree() {
        // Les versions 1.1.0+16 et +17 envoient toujours une note de 1 à 5.
        Decor d = decor("note-ignoree");

        poster(d.auteur(), Map.of("programId", d.programId().toString(), "score", 5.0, "comment", COMMENTAIRE))
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.score").doesNotExist();

        assertThat(scoreEnBase(UUID.fromString(idDeLAvis(d)))).isNull();
    }

    @Test
    void uneNoteHorsBornes_doitResterRefusee() {
        Decor d = decor("note-hors-bornes");

        poster(d.auteur(), Map.of("programId", d.programId().toString(), "score", 7.0, "comment", COMMENTAIRE))
            .expectStatus().isBadRequest();
    }

    // — étape C : la lecture —

    @Test
    void uneNoteAnterieure_neDoitJamaisEtreRendue() {
        Decor d = decor("note-anterieure");
        poster(d.auteur(), Map.of("programId", d.programId().toString(), "comment", COMMENTAIRE))
            .expectStatus().isCreated();
        // Une note saisie avant le 14/09, encore en base jusqu'à l'étape D.
        jdbcTemplate.update("UPDATE reviews SET score = 4 WHERE id = ?", UUID.fromString(idDeLAvis(d)));

        lire(d.organisateur(), "/api/reviews/programs/{id}", d.programId())
            .expectBody()
            .jsonPath("$.content[0].comment").isEqualTo(COMMENTAIRE)
            .jsonPath("$.content[0].score").doesNotExist();
        lire(d.auteur(), "/api/reviews/me", null)
            .expectBody().jsonPath("$.content[0].score").doesNotExist();
    }

    @Test
    void lesAvisDUnProgramme_doiventEtreLusParLOrganisateurEtLAuteurSeulement() {
        Decor d = decor("visibilite");
        poster(d.auteur(), Map.of("programId", d.programId().toString(), "comment", COMMENTAIRE))
            .expectStatus().isCreated();

        lire(d.organisateur(), "/api/reviews/programs/{id}", d.programId())
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].comment").isEqualTo(COMMENTAIRE);

        lire(d.auteur(), "/api/reviews/programs/{id}", d.programId())
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].comment").isEqualTo(COMMENTAIRE);

        // Un visiteur : 200 et une page vide, jamais un 403.
        lire(d.visiteur(), "/api/reviews/programs/{id}", d.programId())
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(0)
            .jsonPath("$.content").isEmpty();
    }

    @Test
    void leResume_doitSuivreLaMemeVisibilite() {
        Decor d = decor("resume");
        poster(d.auteur(), Map.of("programId", d.programId().toString(), "comment", COMMENTAIRE))
            .expectStatus().isCreated();

        lire(d.organisateur(), "/api/reviews/programs/{id}/summary", d.programId())
            .expectBody()
            .jsonPath("$.totalReviews").isEqualTo(1)
            .jsonPath("$.recentReviews[0].comment").isEqualTo(COMMENTAIRE)
            .jsonPath("$.averageScore").doesNotExist();

        lire(d.visiteur(), "/api/reviews/programs/{id}/summary", d.programId())
            .expectBody()
            .jsonPath("$.totalReviews").isEqualTo(0)
            .jsonPath("$.recentReviews").isEmpty();
    }

    // — outils —

    private record Compte(UUID id, String token) {}

    private record Decor(Compte organisateur, Compte auteur, Compte visiteur, UUID programId) {}

    /** Un organisateur, un participant présent à sa séance, et un visiteur sans lien. */
    private Decor decor(String prefixe) {
        Compte organisateur = compte(prefixe + "-orga");
        Compte auteur = compte(prefixe + "-auteur");
        Compte visiteur = compte(prefixe + "-visiteur");
        User orga = userRepository.findById(organisateur.id()).orElseThrow();
        User participant = userRepository.findById(auteur.id()).orElseThrow();

        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(orga).activity(activity).build());
        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Avis " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant debut = Instant.now().minus(3, ChronoUnit.HOURS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle des avis")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("2 place du Capitole, Toulouse")
            .location(geometryFactory.createPoint(new Coordinate(1.4442, 43.6045)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());

        presence(schedule, orga, debut);
        presence(schedule, participant, debut);
        return new Decor(organisateur, auteur, visiteur, program.getId());
    }

    private void presence(Schedule schedule, User user, Instant debut) {
        Attendance attendance = new Attendance();
        attendance.setSchedule(schedule);
        attendance.setUser(user);
        attendance.setWasPresent(true);
        attendance.setAttendedAt(debut);
        attendance.setConfirmedAt(Instant.now());
        attendanceRepository.save(attendance);
    }

    private WebTestClient.ResponseSpec poster(Compte auteur, Map<String, Object> corps) {
        return webTestClient.post().uri("/api/reviews")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange();
    }

    private WebTestClient.ResponseSpec lire(Compte lecteur, String uri, UUID programId) {
        return (programId == null
                ? webTestClient.get().uri(uri)
                : webTestClient.get().uri(uri, programId))
            .headers(h -> h.setBearerAuth(lecteur.token()))
            .exchange().expectStatus().isOk();
    }

    private String idDeLAvis(Decor d) {
        return jdbcTemplate.queryForObject(
            "SELECT id::text FROM reviews WHERE program_id = ? AND reviewer_id = ?",
            String.class, d.programId(), d.auteur().id());
    }

    private Float scoreEnBase(UUID reviewId) {
        return jdbcTemplate.queryForObject("SELECT score FROM reviews WHERE id = ?", Float.class, reviewId);
    }

    private Compte compte(String prefixe) {
        String email = uniqueEmail("avis-" + prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Avis" + UUID.randomUUID().toString().substring(0, 6)))
            .exchange().expectStatus().isCreated();
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return new Compte(userRepository.findByEmail(email).orElseThrow().getId(), auth.accessToken());
    }
}
