package org.program.pair.domain.program.jobs;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.CycleNudge;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.CycleNudgeRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les trois présélections, contre une vraie base.
 *
 * <p>Elles portent l'essentiel du comportement — un {@code GROUP BY … HAVING},
 * deux {@code NOT EXISTS} et une fenêtre bornée des deux côtés — et rien de tout
 * cela ne se teste avec des bouchons. La décision exacte qui les suit est
 * couverte par {@code CycleNudgeJobTest}.
 *
 * <p>Ce qui est vérifié ici en plus, et qui ne se voit qu'en base : que la
 * fenêtre basse écarte réellement l'historique — le programme dont le cycle
 * s'est refermé il y a trois mois ne doit pas être relancé au premier passage du
 * job.
 */
class CycleNudgeJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired CycleNudgeRepository cycleNudgeRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final Instant NOW = Instant.now();
    private static final Instant STAGE7_UNTIL = NOW.minus(Duration.ofHours(24));
    private static final Instant STAGE7_FROM = STAGE7_UNTIL.minus(Duration.ofDays(7)).minus(Duration.ofHours(2));

    // ------------------------------------------------------------------
    // Étape 7
    // ------------------------------------------------------------------

    @Test
    void etape7_retientUnProgrammeDontLaDerniereSeanceEstFinieDepuisPlusDe24h() {
        Program program = program("Cycle refermé");
        schedule(program, NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(28)),
            SlotStatus.PAST);

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .contains(program.getId());
    }

    @Test
    void etape7_ecarteUnProgrammeDontUneSeanceEstEncoreAVenir() {
        Program program = program("Cycle en cours");
        schedule(program, NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)),
            SlotStatus.PAST);
        schedule(program, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)),
            SlotStatus.OPEN);

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .doesNotContain(program.getId());
    }

    @Test
    void etape7_ecarteLHistorique_pourQueLePremierPassageNeRelancePasTout() {
        // La borne basse. Sans elle, ce programme — et tous ses semblables —
        // partirait d'un coup au premier passage du job.
        Program program = program("Cycle refermé il y a trois mois");
        schedule(program, NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)),
            SlotStatus.PAST);

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .doesNotContain(program.getId());
    }

    @Test
    void etape7_ecarteUnProgrammeDejaRelance() {
        Program program = program("Déjà relancé");
        schedule(program, NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(28)),
            SlotStatus.PAST);

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .contains(program.getId());

        cycleNudgeRepository.save(CycleNudge.builder()
            .program(program)
            .user(program.getUserActivity().getUser())
            .stage((short) 7)
            .sentAt(NOW)
            .build());

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .doesNotContain(program.getId());
    }

    @Test
    void etape7_retientUneSeanceLongueEncoreEnCours_etLaisseJavaLEcarter() {
        // La présélection porte sur MAX(starts_at) : une séance commencée il y a
        // 25 h y entre alors qu'elle n'est pas finie. C'est voulu — une
        // présélection a le droit d'être trop large, jamais trop étroite — et
        // c'est ProgramCycle qui l'écarte ensuite.
        Program program = program("Randonnée de deux jours");
        schedule(program, NOW.minus(Duration.ofHours(25)), NOW.plus(Duration.ofHours(1)),
            SlotStatus.OPEN);

        assertThat(programRepository.findStage7Candidates(STAGE7_FROM, STAGE7_UNTIL))
            .contains(program.getId());
    }

    // ------------------------------------------------------------------
    // Étape 2
    // ------------------------------------------------------------------

    @Test
    void etape2_retientUnProgrammeSansAucunCreneau() {
        Program program = program("Sans date", NOW.minus(Duration.ofDays(8)));

        assertThat(programRepository.findStage2Candidates(
                NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofDays(7))))
            .contains(program.getId());
    }

    @Test
    void etape2_retientUnProgrammeDontLUniqueCreneauEstAnnule() {
        // « Aucun créneau non annulé », et non « aucun créneau » : c'est
        // exactement la population que le contrat désigne — plus de pin sur la
        // carte.
        Program program = program("Créneau annulé", NOW.minus(Duration.ofDays(8)));
        schedule(program, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)),
            SlotStatus.CANCELLED);

        assertThat(programRepository.findStage2Candidates(
                NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofDays(7))))
            .contains(program.getId());
    }

    @Test
    void etape2_ecarteUnProgrammeQuiAUnCreneauVivant() {
        Program program = program("Avec date", NOW.minus(Duration.ofDays(8)));
        schedule(program, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)),
            SlotStatus.OPEN);

        assertThat(programRepository.findStage2Candidates(
                NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofDays(7))))
            .doesNotContain(program.getId());
    }

    // ------------------------------------------------------------------
    // Étape 4
    // ------------------------------------------------------------------

    @Test
    void etape4_retientUnProgrammePublieDepuis48hSansAucunInscrit() {
        Program program = program("Publié, vide");
        Schedule slot = schedule(program, NOW.plus(Duration.ofDays(5)),
            NOW.plus(Duration.ofDays(5)), SlotStatus.OPEN);
        backdateCreation(slot, NOW.minus(Duration.ofDays(3)));

        assertThat(programRepository.findStage4Candidates(
                NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(48)),
                NOW.plus(Duration.ofHours(48))))
            .contains(program.getId());
    }

    @Test
    void etape4_ecarteUnProgrammeDontLaProchaineSeanceEstDansMoinsDe48h() {
        Program program = program("Séance demain");
        Schedule slot = schedule(program, NOW.plus(Duration.ofHours(20)),
            NOW.plus(Duration.ofHours(22)), SlotStatus.OPEN);
        backdateCreation(slot, NOW.minus(Duration.ofDays(3)));

        assertThat(programRepository.findStage4Candidates(
                NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(48)),
                NOW.plus(Duration.ofHours(48))))
            .doesNotContain(program.getId());
    }

    @Test
    void etape4_ecarteUnProgrammePublieIlYaMoinsDe48h() {
        Program program = program("Publié ce matin");
        schedule(program, NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(5)),
            SlotStatus.OPEN);

        assertThat(programRepository.findStage4Candidates(
                NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(48)),
                NOW.plus(Duration.ofHours(48))))
            .doesNotContain(program.getId());
    }

    // ------------------------------------------------------------------

    private Program program(String title) {
        return program(title, null);
    }

    private Program program(String title, Instant createdAt) {
        // Un auteur par programme. Les tests de cette classe ne se déroulent pas
        // dans une transaction annulée, et {@code uq_user_activity} interdit deux
        // fois le même couple (personne, activité) : partager l'auteur du seed
        // ferait échouer le second test de la classe, quel qu'il soit.
        User host = userRepository.save(User.builder()
            .email("cycle-" + UUID.randomUUID() + "@test.meetdo")
            .passwordHash("x")
            .displayName("Auteur de test")
            .isActive(true)
            .build());

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title + " " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        if (createdAt != null) {
            // created_at est @CreatedDate : Spring Data le pose à l'insertion et
            // ignore toute valeur donnée par le mapping. Le repositionner en SQL
            // est le seul moyen de tester une fenêtre qui porte dessus.
            jdbcTemplate.update("UPDATE programs SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(createdAt), program.getId());
        }
        return program;
    }

    private Schedule schedule(Program program, Instant startsAt, Instant endsAt, SlotStatus status) {
        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Lieu de test")
            .placeType(PlaceType.ONLINE)
            .startsAt(startsAt)
            .endsAt(endsAt)
            .status(status)
            .build());
    }

    /** Même raison que pour les programmes : {@code created_at} est @CreatedDate. */
    private void backdateCreation(Schedule slot, Instant createdAt) {
        jdbcTemplate.update("UPDATE schedules SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(createdAt), slot.getId());
    }
}
