package org.program.pair.domain.program.jobs;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramService;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.CreateScheduleRequest;
import org.program.pair.domain.program.dto.UpdateProgramRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le sommeil : qui s'endort, qui ne s'endort pas, et ce que le sommeil produit.
 *
 * <p>Le point du lot est qu'il <b>n'a demandé aucun filtre nouveau</b> : toutes
 * les surfaces publiques bornent déjà sur {@code status = 'ACTIVE'}. Les tests
 * de la seconde moitié vérifient cette affirmation plutôt que de la croire —
 * c'est elle qui décide si la fonctionnalité rend le service attendu.
 */
class ProgramDormancyJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ProgramService programService;
    @Autowired ProgramDormancyJob job;
    @Autowired JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final double LAT = -12.0;
    private static final double LNG = -77.0;

    // ------------------------------------------------------------------
    // Qui s'endort
    // ------------------------------------------------------------------

    @Test
    void unProgrammeSansCreneau_sEndortApresLeDelai() {
        Program program = program(ProgramStatus.ACTIVE, daysAgo(8));

        runJob(7);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.DORMANT);
    }

    @Test
    void unProgrammeDontLUniqueCreneauEstAnnule_sEndortAussi() {
        // « Aucun créneau non annulé », et non « aucun créneau » : il n'a plus de
        // pin sur la carte, et c'est exactement la population visée.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(8));
        schedule(program, Instant.now().plus(Duration.ofDays(3)), SlotStatus.CANCELLED);

        runJob(7);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.DORMANT);
    }

    @Test
    void unProgrammeQuiAUnCreneauVivant_neSEndortPas() {
        Program program = program(ProgramStatus.ACTIVE, daysAgo(30));
        schedule(program, Instant.now().plus(Duration.ofDays(3)), SlotStatus.OPEN);

        runJob(7);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    @Test
    void unProgrammeDontLeCreneauEstPasse_neSEndortPas() {
        // Il a eu sa date. Le sommeil vise les coquilles vides, pas les
        // programmes finis — ceux-là relèvent de l'étape 7 du cycle.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(30));
        schedule(program, Instant.now().minus(Duration.ofDays(10)), SlotStatus.PAST);

        runJob(7);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    @Test
    void unProgrammeTropRecent_neSEndortPas() {
        Program program = program(ProgramStatus.ACTIVE, daysAgo(2));

        runJob(7);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    @Test
    void unBrouillonEtUnProgrammeEnPause_neSEndormentPas() {
        // DRAFT n'a jamais été publié, PAUSED est une décision de son auteur :
        // ni l'un ni l'autre n'est une coquille vide oubliée.
        Program brouillon = program(ProgramStatus.DRAFT, daysAgo(30));
        Program enPause = program(ProgramStatus.PAUSED, daysAgo(30));

        runJob(7);

        assertThat(statusOf(brouillon)).isEqualTo(ProgramStatus.DRAFT);
        assertThat(statusOf(enPause)).isEqualTo(ProgramStatus.PAUSED);
    }

    @Test
    void unDelaiNul_eteintLeJob() {
        Program program = program(ProgramStatus.ACTIVE, daysAgo(30));

        runJob(0);

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // Ce que le sommeil produit
    // ------------------------------------------------------------------

    @Test
    void unProgrammeDormant_sortDeLaCarte() {
        // Le programme porte un créneau localisé : sans le filtre de statut, il
        // serait sur la carte. C'est la vérification que DORMANT suffit, sans
        // qu'aucune requête n'ait eu à être touchée.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(1));
        schedule(program, Instant.now().plus(Duration.ofDays(3)), SlotStatus.OPEN);

        assertThat(programRepository.findVisibleNearScheduleOrOrganizerIds(LAT, LNG, 20_000, 100))
            .contains(program.getId());

        setStatus(program, ProgramStatus.DORMANT);

        assertThat(programRepository.findVisibleNearScheduleOrOrganizerIds(LAT, LNG, 20_000, 100))
            .doesNotContain(program.getId());
    }

    @Test
    void unProgrammeDormant_resteVisibleDeSonAuteur() {
        // Sans quoi le réveil serait impossible : on ne réveille pas ce qu'on ne
        // voit plus.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(8));
        UUID authorId = program.getUserActivity().getUser().getId();

        runJob(7);

        assertThat(programService.getMyPrograms(authorId))
            .extracting(dto -> dto.id())
            .contains(program.getId());
        assertThat(programService.getProgram(program.getId(), authorId).status())
            .isEqualTo("DORMANT");
    }

    // ------------------------------------------------------------------
    // Le réveil
    // ------------------------------------------------------------------

    @Test
    void poserUnCreneau_reveilleLeProgramme() {
        // Tenu côté serveur, et non laissé au client : un programme DORMANT qui a
        // pourtant un pin serait invisible sur la carte sans que personne puisse
        // le comprendre.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(8));
        UUID authorId = program.getUserActivity().getUser().getId();

        runJob(7);
        assertThat(statusOf(program)).isEqualTo(ProgramStatus.DORMANT);

        programService.addSchedule(authorId, program.getId(), new CreateScheduleRequest(
            "Lieu du réveil", PlaceType.ONLINE, null, null, null, null, null,
            Instant.now().plus(Duration.ofDays(2)), Instant.now().plus(Duration.ofDays(2)),
            null, null, null, null, null, null));

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    @Test
    void lAuteurPeutReveillerExplicitement() {
        // Le bouton « Le réveiller », pour qui veut ressortir son programme avant
        // même de lui poser une date.
        Program program = program(ProgramStatus.ACTIVE, daysAgo(8));
        UUID authorId = program.getUserActivity().getUser().getId();

        runJob(7);
        assertThat(statusOf(program)).isEqualTo(ProgramStatus.DORMANT);

        programService.updateProgram(authorId, program.getId(), new UpdateProgramRequest(
            null, null, ProgramStatus.ACTIVE, null, null, null, null, null,
            null, null, null, null, null, null, null));

        assertThat(statusOf(program)).isEqualTo(ProgramStatus.ACTIVE);
    }

    // ------------------------------------------------------------------

    private void runJob(int delayDays) {
        ReflectionTestUtils.setField(job, "dormancyDelayDays", delayDays);
        ReflectionTestUtils.setField(job, "nudgeDelayDays", 3);
        job.putEmptyProgramsToSleep();
    }

    private ProgramStatus statusOf(Program program) {
        return programRepository.findById(program.getId()).orElseThrow().getStatus();
    }

    private void setStatus(Program program, ProgramStatus status) {
        Program fresh = programRepository.findById(program.getId()).orElseThrow();
        fresh.setStatus(status);
        programRepository.save(fresh);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private Program program(ProgramStatus status, Instant createdAt) {
        User host = userRepository.save(User.builder()
            .email("dormancy-" + UUID.randomUUID() + "@test.meetdo")
            .passwordHash("x")
            .displayName("Auteur de test")
            .isActive(true)
            .build());

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Sommeil " + UUID.randomUUID())
            .status(status)
            .isPublic(true)
            .build());

        // created_at est @CreatedDate : Spring Data le pose à l'insertion et
        // ignore toute valeur donnée par le mapping.
        jdbcTemplate.update("UPDATE programs SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(createdAt), program.getId());

        return program;
    }

    private void schedule(Program program, Instant startsAt, SlotStatus status) {
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Lieu de test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Sommeil")
            .showExactAddress(true)
            .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(Duration.ofHours(1)))
            .status(status)
            .build());
    }
}
