package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que vaut {@code ProgramDto.nextSessionAt} pendant qu'une séance a lieu —
 * la question du client, et sa réponse.
 *
 * <p>Le calcul était {@code min(startsAt)} parmi les créneaux dont le début est
 * futur. Une séance en cours en était donc exclue, et le champ valait
 * <b>{@code null} pendant la séance</b>. Chez le client, {@code schedules} non
 * vide plus {@code nextSessionAt} nul vaut {@code programIsExpired()}, prédicat
 * qui commande trois choses : le programme quitte la carte, ses tuiles grisent,
 * et « Rejoindre » disparaît. Tout cela se produisait <b>au moment précis où la
 * séance avait lieu</b>.
 *
 * <p>Le second cas testé ici n'était pas dans la demande : un créneau
 * <b>annulé</b> mais futur alimentait le champ, si bien qu'un programme sans un
 * seul créneau vivant paraissait avoir un pin sur la carte. C'est la frontière
 * même sur laquelle repose le module de relance.
 */
class NextSessionAtIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ProgramService programService;

    @Test
    void pendantLaSeance_nextSessionAtResteLaSeanceEnCours() {
        // Démarrée il y a 20 minutes, elle finit dans 40 : exactement le cas de
        // la question.
        Program program = program();
        Instant debut = Instant.now().minus(Duration.ofMinutes(20));
        schedule(program, debut, Instant.now().plus(Duration.ofMinutes(40)), SlotStatus.OPEN);

        ProgramDto dto = read(program);

        assertThat(dto.nextSessionAt())
            .as("null ferait rendre true à programIsExpired() pendant le cours")
            .isNotNull();
        assertThat(dto.nextSessionAt()).isCloseTo(debut, within2Seconds());
    }

    @Test
    void pendantUneSeanceSansFinDeclaree_nextSessionAtResteLaSeanceEnCours() {
        // endsAt est facultative à la création : la convention de SlotTiming
        // (deux heures) prend le relais, et une séance commencée il y a 20
        // minutes n'est pas finie.
        Program program = program();
        Instant debut = Instant.now().minus(Duration.ofMinutes(20));
        schedule(program, debut, null, SlotStatus.OPEN);

        assertThat(read(program).nextSessionAt()).isNotNull();
    }

    @Test
    void apresLaSeance_nextSessionAtRedevientNul() {
        // La correction ne doit pas rendre les programmes éternellement vivants :
        // une fois la séance finie, le champ retombe à null comme avant.
        Program program = program();
        schedule(program, Instant.now().minus(Duration.ofHours(3)),
            Instant.now().minus(Duration.ofHours(1)), SlotStatus.OPEN);

        assertThat(read(program).nextSessionAt()).isNull();
    }

    @Test
    void unCreneauAnnuleNalimentePasNextSessionAt() {
        // Un programme dont l'unique créneau est annulé n'a plus de pin sur la
        // carte. Le champ doit le dire.
        Program program = program();
        schedule(program, Instant.now().plus(Duration.ofDays(2)),
            Instant.now().plus(Duration.ofDays(2)), SlotStatus.CANCELLED);

        assertThat(read(program).nextSessionAt()).isNull();
    }

    @Test
    void uneSeanceAVenirNonAnnuleeAlimenteToujoursLeChamp() {
        Program program = program();
        Instant debut = Instant.now().plus(Duration.ofDays(2));
        schedule(program, debut, debut.plus(Duration.ofHours(1)), SlotStatus.OPEN);

        assertThat(read(program).nextSessionAt()).isCloseTo(debut, within2Seconds());
    }

    @Test
    void laSeanceEnCoursPasseDevantCelleQuiVient() {
        // Deux créneaux : un en cours, un dans huit jours. « La prochaine » est
        // celle qu'on est en train de vivre, pas la suivante.
        Program program = program();
        Instant enCours = Instant.now().minus(Duration.ofMinutes(20));
        schedule(program, enCours, Instant.now().plus(Duration.ofMinutes(40)), SlotStatus.OPEN);
        schedule(program, Instant.now().plus(Duration.ofDays(8)),
            Instant.now().plus(Duration.ofDays(8)), SlotStatus.OPEN);

        assertThat(read(program).nextSessionAt()).isCloseTo(enCours, within2Seconds());
    }

    // ------------------------------------------------------------------

    private static org.assertj.core.data.TemporalUnitOffset within2Seconds() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
            2, java.time.temporal.ChronoUnit.SECONDS);
    }

    private ProgramDto read(Program program) {
        return programService.getProgram(
            program.getId(), program.getUserActivity().getUser().getId());
    }

    private Program program() {
        // Un auteur par programme : uq_user_activity interdit deux fois le même
        // couple (personne, activité), et les tests de cette classe ne se
        // déroulent pas dans une transaction annulée.
        User host = userRepository.save(User.builder()
            .email("next-session-" + UUID.randomUUID() + "@test.meetdo")
            .passwordHash("x")
            .displayName("Auteur de test")
            .isActive(true)
            .build());

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        return programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Programme nextSessionAt " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    private void schedule(Program program, Instant startsAt, Instant endsAt, SlotStatus status) {
        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Lieu de test")
            .placeType(PlaceType.ONLINE)
            .startsAt(startsAt)
            .endsAt(endsAt)
            .status(status)
            .build());
    }
}
