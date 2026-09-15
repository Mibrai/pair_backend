package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * « Recommandé par des participants » — demande mobile avis du 15/09 : un booléen,
 * à partir de trois personnes distinctes présentes et qui recommandent, jamais un
 * nombre.
 */
class RecommandeParParticipantsIntegrationTest extends AbstractIntegrationTest {

    private static final String AVIS = "Séance très bien menée, ambiance accueillante et rythme adapté.";

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void deuxRecommandations_neSuffisentPas_troisOui() {
        Decor d = decor("reco-seuil");
        Compte lecteur = compte("reco-lecteur");

        recommander(d, participantPresent(d, "p1"), true);
        recommander(d, participantPresent(d, "p2"), true);
        assertThat(recommande(lecteur, d.programId())).isFalse();

        recommander(d, participantPresent(d, "p3"), true);
        assertThat(recommande(lecteur, d.programId())).isTrue();
    }

    @Test
    void neComptentPas_lOrganisateur_niUnAvisSansRecommandation_niUneRecommandationSansPresence() {
        Decor d = decor("reco-exclus");
        Compte lecteur = compte("reco-exclus-lecteur");

        recommander(d, participantPresent(d, "p1"), true);
        recommander(d, participantPresent(d, "p2"), true);
        // Un avis sans « oui ».
        recommander(d, participantPresent(d, "p3"), false);
        // Une recommandation de l'organisateur lui-même, posée en base : la route
        // la refuse, la règle ne doit pas la compter pour autant.
        jdbcTemplate.update("INSERT INTO reviews (id, program_id, reviewer_id, comment, recommend, created_at) "
            + "VALUES (gen_random_uuid(), ?, ?, 'x', TRUE, now())", d.programId(), d.organisateur().id());
        // Une recommandation sans présence confirmée, posée en base aussi.
        Compte absent = compte("reco-absent");
        jdbcTemplate.update("INSERT INTO reviews (id, program_id, reviewer_id, comment, recommend, created_at) "
            + "VALUES (gen_random_uuid(), ?, ?, 'x', TRUE, now())", d.programId(), absent.id());

        assertThat(recommande(lecteur, d.programId())).isFalse();
    }

    @Test
    void uneRecommandation_bloqueeAvecLOrganisateur_ouDUnCompteFerme_neComptePas() {
        Decor d = decor("reco-blocage");
        Compte lecteur = compte("reco-blocage-lecteur");
        recommander(d, participantPresent(d, "p1"), true);
        recommander(d, participantPresent(d, "p2"), true);
        Compte bloque = participantPresent(d, "p3");
        recommander(d, bloque, true);
        assertThat(recommande(lecteur, d.programId())).isTrue();

        webTestClient.post().uri("/api/users/{id}/block", bloque.id())
            .headers(h -> h.setBearerAuth(d.organisateur().token()))
            .exchange().expectStatus().isNoContent();
        assertThat(recommande(lecteur, d.programId())).isFalse();

        Compte quatrieme = participantPresent(d, "p4");
        recommander(d, quatrieme, true);
        assertThat(recommande(lecteur, d.programId())).isTrue();
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", quatrieme.id());
        assertThat(recommande(lecteur, d.programId())).isFalse();
    }

    @Test
    void laRecommandation_estRendueALAuteur_etAucunNombreNeSortAuContrat() {
        Decor d = decor("reco-contrat");
        Compte auteur = participantPresent(d, "p1");
        recommander(d, auteur, true);

        webTestClient.get().uri("/api/reviews/me")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.content[0].recommend").isEqualTo(true);

        String corps = webTestClient.get().uri("/v3/api-docs")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        JsonNode propriete;
        try {
            propriete = objectMapper.readTree(corps)
                .at("/components/schemas/ProgramDto/properties/recommendedByParticipants");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(propriete.path("type").asText()).isEqualTo("boolean");
        assertThat(corps).doesNotContain("recommendationCount").doesNotContain("recommendedCount");
    }

    // — outils —

    private record Compte(UUID id, String token) {}

    private record Decor(Compte organisateur, UUID programId, UUID scheduleId, Instant debut) {}

    private Boolean recommande(Compte lecteur, UUID programId) {
        Map<?, ?> programme = webTestClient.get().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(lecteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(programme).isNotNull();
        return (Boolean) programme.get("recommendedByParticipants");
    }

    private void recommander(Decor d, Compte auteur, boolean oui) {
        webTestClient.post().uri("/api/reviews")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("programId", d.programId().toString(), "comment", AVIS, "recommend", oui))
            .exchange().expectStatus().isCreated();
    }

    /** Un participant présent à la séance, comme l'organisateur : il peut écrire un avis. */
    private Compte participantPresent(Decor d, String suffixe) {
        Compte c = compte("reco-" + suffixe);
        presence(d.scheduleId(), userRepository.findById(c.id()).orElseThrow(), d.debut());
        return c;
    }

    private Decor decor(String prefixe) {
        Compte organisateur = compte(prefixe + "-orga");
        User orga = userRepository.findById(organisateur.id()).orElseThrow();
        UserActivity ua = userActivityRepository.save(
            UserActivity.builder().user(orga).activity(activityRepository.findAll().get(0)).build());
        Program program = programRepository.save(Program.builder()
            .userActivity(ua)
            .title("Recommandé " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
        Instant debut = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle des recommandations")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 place de la Comédie, Montpellier")
            .location(geometryFactory.createPoint(new Coordinate(3.8767, 43.6108)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());
        presence(schedule.getId(), orga, debut);
        return new Decor(organisateur, program.getId(), schedule.getId(), debut);
    }

    private void presence(UUID scheduleId, User user, Instant debut) {
        Attendance attendance = new Attendance();
        attendance.setSchedule(scheduleRepository.findById(scheduleId).orElseThrow());
        attendance.setUser(user);
        attendance.setWasPresent(true);
        attendance.setAttendedAt(debut);
        attendance.setConfirmedAt(Instant.now());
        attendanceRepository.save(attendance);
    }

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Testeur"))
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
