package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.dto.AttendanceDto;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit le bug de production : POST /api/attendances/{scheduleId}/confirm
 * renvoie 500 quand wasPresent=true (mais 200 quand wasPresent=false), alors
 * que la seule différence de chemin est l'appel à
 * PracticeStatsService.recalculateFor(). Le premier "true" d'un utilisateur
 * (attendanceCount 0 -> 1, aucun autre participant présent) est le cas le
 * plus fragile pour les agrégats — c'est exactement celui testé ici.
 */
class AttendanceConfirmIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void confirm_wasPresentTrue_devraitReussir_pourLaPremierePresenceDUnUtilisateur() {
        String hostEmail = "confirm-bug-host@pair.app";
        String token = registerAndLogin(hostEmail);
        User host = userRepository.findByEmail(hostEmail).orElseThrow();

        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Session test confirmation")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(Instant.now().minus(3, ChronoUnit.HOURS))
            .endsAt(Instant.now().minus(2, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());

        AttendanceDto result = webTestClient.post()
            .uri("/api/attendances/{scheduleId}/confirm", schedule.getId())
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"wasPresent\":true}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(AttendanceDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.wasPresent()).isTrue();

        User refreshed = userRepository.findById(host.getId()).orElseThrow();
        assertThat(refreshed.getAttendanceCount()).isEqualTo(1);
        assertThat(refreshed.getDistinctPartnersCount()).isEqualTo(0);
    }

    private String registerAndLogin(String email) {
        org.program.pair.domain.auth.dto.RegisterRequest registerReq =
            new org.program.pair.domain.auth.dto.RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        org.program.pair.domain.auth.dto.LoginRequest loginReq =
            new org.program.pair.domain.auth.dto.LoginRequest(email, "Password123!");
        org.program.pair.domain.auth.dto.AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(org.program.pair.domain.auth.dto.AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
