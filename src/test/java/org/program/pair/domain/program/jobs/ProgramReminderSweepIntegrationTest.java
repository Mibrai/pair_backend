package org.program.pair.domain.program.jobs;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le {@code WHERE} du balayage T-2h, contre une vraie base.
 *
 * <p>C'est lui, et non du code Java, qui porte trois des propriétés promises au
 * client : un créneau annulé n'est pas rappelé, un créneau déplacé l'est à
 * nouveau, et une séance déjà commencée ne l'est plus. Un test à mocks aurait
 * validé l'appel sans jamais exécuter la condition.
 */
class ProgramReminderSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    @Test
    void balayage_doitPrendreLesCreneauxDeMoinsDeDeuxHeures_etEuxSeuls() {
        Instant now = Instant.now();

        Schedule dans90min = persistSlot(now.plus(90, ChronoUnit.MINUTES), SlotStatus.OPEN);
        Schedule dans5h = persistSlot(now.plus(5, ChronoUnit.HOURS), SlotStatus.OPEN);
        Schedule dejaCommence = persistSlot(now.minus(10, ChronoUnit.MINUTES), SlotStatus.OPEN);

        List<UUID> dus = dueIds(now);

        assertThat(dus).contains(dans90min.getId());
        // Trop loin : il sera pris quand il entrera dans la fenêtre.
        assertThat(dus).doesNotContain(dans5h.getId());
        // Déjà commencé : rappeler une séance en cours n'a pas de sens, et c'est
        // ce qui protège d'une salve rétroactive après un arrêt du service.
        assertThat(dus).doesNotContain(dejaCommence.getId());
    }

    @Test
    void creneauAnnule_neDoitJamaisEtreRappele() {
        // Question n°2 du client, premier volet. Il n'y a pas d'annulation de
        // rappel à faire : un créneau CANCELLED sort simplement du balayage.
        Instant now = Instant.now();
        Schedule annule = persistSlot(now.plus(90, ChronoUnit.MINUTES), SlotStatus.CANCELLED);

        assertThat(dueIds(now)).doesNotContain(annule.getId());
    }

    @Test
    void creneauDejaRappele_neDoitPasLEtreDeuxFois() {
        Instant now = Instant.now();
        Schedule slot = persistSlot(now.plus(90, ChronoUnit.MINUTES), SlotStatus.OPEN);

        assertThat(dueIds(now)).contains(slot.getId());

        // Ce que le job écrit après avoir notifié.
        slot.setReminderSentFor(slot.getStartsAt());
        scheduleRepository.saveAndFlush(slot);

        assertThat(dueIds(now)).doesNotContain(slot.getId());
    }

    @Test
    void creneauDeplace_doitRedevenirEligible_sansReplanification() {
        // Question n°2 du client, second volet — et la raison d'être de la
        // colonne : elle mémorise POUR QUEL starts_at le rappel est parti, pas
        // qu'il est parti. Déplacer le créneau suffit à le rendre à nouveau dû,
        // sans qu'aucun chemin de déplacement ait à s'en occuper.
        Instant now = Instant.now();
        Schedule slot = persistSlot(now.plus(90, ChronoUnit.MINUTES), SlotStatus.OPEN);
        slot.setReminderSentFor(slot.getStartsAt());
        scheduleRepository.saveAndFlush(slot);

        assertThat(dueIds(now)).doesNotContain(slot.getId());

        // Le créneau est déplacé d'une demi-heure. Rien d'autre n'est touché.
        slot.setStartsAt(now.plus(120, ChronoUnit.MINUTES));
        scheduleRepository.saveAndFlush(slot);

        assertThat(dueIds(now)).contains(slot.getId());
    }

    private List<UUID> dueIds(Instant now) {
        return scheduleRepository.findDueForReminder(now, now.plus(2, ChronoUnit.HOURS))
            .stream().map(Schedule::getId).toList();
    }

    private Schedule persistSlot(Instant startsAt, SlotStatus status) {
        User host = userRepository.findById(
            UUID.fromString("00000000-0000-0000-0000-000000000001")).orElseThrow();
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();

        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Programme test rappel T-2h")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.saveAndFlush(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .startsAt(startsAt)
            .status(status)
            .build());
    }
}
