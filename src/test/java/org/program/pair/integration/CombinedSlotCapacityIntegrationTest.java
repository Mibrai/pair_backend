package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramEnrollmentService;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un même Schedule peut recevoir des participants par deux mécanismes distincts :
 * UserProgram (inscription à un programme structuré) et SlotParticipation
 * (RSVP léger sur un créneau ouvert, voir SlotService). maxParticipants doit
 * être respecté par la somme des deux, jamais par l'un isolément — sinon le
 * créneau peut être sur-réservé en combinant les deux points d'entrée.
 */
class CombinedSlotCapacityIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotService slotService;
    @Autowired ProgramEnrollmentService programEnrollmentService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void capaciteCombinee_neDoitJamaisDepasserMaxParticipants() {
        String hostEmail = "capa-host@pair.app";
        String slotJoinerEmail = "capa-slot@pair.app";
        String programJoinerEmail = "capa-program@pair.app";

        register(hostEmail);
        register(slotJoinerEmail);
        register(programJoinerEmail);

        User host = userRepository.findByEmail(hostEmail).orElseThrow();
        User slotJoiner = userRepository.findByEmail(slotJoinerEmail).orElseThrow();
        User programJoiner = userRepository.findByEmail(programJoinerEmail).orElseThrow();

        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Yoga capacité limitée")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(Instant.now().plus(1, ChronoUnit.DAYS))
            .maxParticipants(1)
            .isOpenToPartners(true)
            .build());

        // Premier participant : rejoint via le RSVP léger (SlotParticipation).
        slotService.joinSlot(slotJoiner.getId(), schedule.getId(), new JoinSlotRequest(null));

        // Second participant : tente de rejoindre le MÊME créneau via
        // l'inscription au programme structuré (UserProgram). Doit être
        // refusé malgré le fait qu'aucun UserProgram n'existe encore pour ce
        // schedule — la capacité est bien vérifiée toutes sources confondues.
        assertThatThrownBy(() -> programEnrollmentService.joinProgram(
                programJoiner.getId(), program.getId(), schedule.getId()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("full");
    }

    private void register(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();
    }
}
