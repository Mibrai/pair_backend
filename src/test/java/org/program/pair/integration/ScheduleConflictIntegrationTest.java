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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1, de bout en bout : rejoindre un créneau qui chevauche un engagement déjà
 * pris doit répondre {@code 409 SCHEDULE_CONFLICT} avec le tableau
 * {@code conflicts}, et le refus doit valoir quel que soit le chemin d'entrée.
 *
 * <p>Le scénario reproduit la course que la vérification côté client ne sait pas
 * fermer : l'engagement existe déjà en base au moment du join — peu importe quel
 * appareil l'a posé.
 */
class ScheduleConflictIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void joinSlot_quiChevaucheUnEngagement_doitRendre409AvecConflits() {
        String hostEmail = "conflict-host@pair.app";
        String joinerEmail = "conflict-joiner@pair.app";
        register(hostEmail);
        register(joinerEmail);

        User host = userRepository.findByEmail(hostEmail).orElseThrow();
        Instant mondayNext = Instant.now().plus(3, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.HOURS);

        // Deux créneaux du même hôte, qui se chevauchent : 18h00-19h15 et 18h30.
        Schedule yoga = openSlot(host, "Yoga du soir",
            mondayNext, mondayNext.plus(75, ChronoUnit.MINUTES), "FREQ=WEEKLY;BYDAY=MO");
        Schedule escalade = openSlot(host, "Escalade",
            mondayNext.plus(30, ChronoUnit.MINUTES), null, "FREQ=WEEKLY;BYDAY=MO");

        String token = login(joinerEmail);

        // Premier engagement : accepté.
        webTestClient.post()
            .uri("/api/slots/" + yoga.getId() + "/join")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isCreated();

        // Second, en chevauchement : 409 avec l'enveloppe complète.
        webTestClient.post()
            .uri("/api/slots/" + escalade.getId() + "/join")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SCHEDULE_CONFLICT")
            .jsonPath("$.message").isNotEmpty()
            .jsonPath("$.conflicts").isArray()
            .jsonPath("$.conflicts[0].scheduleId").isEqualTo(escalade.getId().toString())
            .jsonPath("$.conflicts[0].conflictingScheduleId").isEqualTo(yoga.getId().toString())
            .jsonPath("$.conflicts[0].conflictingProgramTitle").isEqualTo("Yoga du soir")
            .jsonPath("$.conflicts[0].conflictingEngagementType").isEqualTo("SLOT")
            .jsonPath("$.conflicts[0].occurrenceAt").isNotEmpty()
            .jsonPath("$.conflicts[0].conflictingOccurrenceAt").isNotEmpty()
            .jsonPath("$.conflicts[0].conflictingEndsAt").isNotEmpty();
    }

    @Test
    void joinProgram_quiChevaucheUnRsvpCreneau_doitEtreRefuseAussi() {
        // Le refus doit valoir sur l'AUTRE chemin d'entrée : engagement pris par
        // RSVP créneau, tentative par inscription au programme.
        String hostEmail = "conflict-host2@pair.app";
        String joinerEmail = "conflict-joiner2@pair.app";
        register(hostEmail);
        register(joinerEmail);

        User host = userRepository.findByEmail(hostEmail).orElseThrow();
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        Schedule engaged = openSlot(host, "Course à pied",
            start, start.plus(60, ChronoUnit.MINUTES), null);
        Schedule overlapping = openSlot(host, "Natation",
            start.plus(15, ChronoUnit.MINUTES), start.plus(75, ChronoUnit.MINUTES), null);

        String token = login(joinerEmail);

        webTestClient.post()
            .uri("/api/slots/" + engaged.getId() + "/join")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isCreated();

        webTestClient.post()
            .uri("/api/programs/" + overlapping.getProgram().getId() + "/join")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"scheduleId\":\"" + overlapping.getId() + "\"}")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SCHEDULE_CONFLICT")
            .jsonPath("$.conflicts[0].conflictingScheduleId").isEqualTo(engaged.getId().toString());
    }

    // — aides —

    private Schedule openSlot(User host, String title, Instant startsAt, Instant endsAt,
                              String rrule) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        // (user, activity) est unique en base : réutiliser la déclaration existante
        // quand le même hôte publie un second programme sur la même activité.
        UserActivity ua = userActivityRepository.findByUserIdAndActivityId(host.getId(), yoga.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(host).activity(yoga).build()));

        Program program = programRepository.save(Program.builder()
            .userActivity(ua)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(startsAt)
            .endsAt(endsAt)
            .recurrenceRule(rrule)
            .isOpenToPartners(true)
            .build());
    }

    private void register(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();
    }

    private String login(String email) {
        AuthResponse response = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();
        assertThat(response).isNotNull();
        return response.accessToken();
    }
}
