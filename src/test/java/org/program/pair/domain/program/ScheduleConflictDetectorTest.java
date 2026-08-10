package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.program.dto.ScheduleConflictDto;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * La règle B1/B9 : un chevauchement doit être vu même quand il ne se produit pas
 * sur les premières occurrences — c'est le cas que la comparaison des seuls
 * startsAt rate, et la raison d'être du développement RFC 5545 des deux côtés.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleConflictDetectorTest {

    @Mock UserProgramRepository userProgramRepository;
    @Mock SlotParticipationRepository slotParticipationRepository;

    private ScheduleConflictDetector detector() {
        return new ScheduleConflictDetector(
            userProgramRepository, slotParticipationRepository,
            new RecurrenceExpander("Europe/Paris"));
    }

    private final UUID userId = UUID.randomUUID();

    @Test
    void deuxHebdomadairesQuiSeChevauchent_doiventEtreEnConflit() {
        // Engagement : yoga tous les lundis 18h00-19h15 (via RSVP créneau).
        Schedule yoga = schedule("Yoga du soir", nextMonday(18, 0), 75, "FREQ=WEEKLY;BYDAY=MO");
        stubEngagements(List.of(), List.of(slotParticipation(yoga)));

        // Cible : escalade tous les lundis 18h30 (durée inconnue -> 60 min supposées).
        Schedule escalade = schedule("Escalade", nextMonday(18, 30), null, "FREQ=WEEKLY;BYDAY=MO");

        List<ScheduleConflictDto> conflicts = detector().detect(userId, List.of(escalade));

        assertThat(conflicts).isNotEmpty();
        ScheduleConflictDto first = conflicts.get(0);
        assertThat(first.scheduleId()).isEqualTo(escalade.getId());
        assertThat(first.conflictingScheduleId()).isEqualTo(yoga.getId());
        assertThat(first.conflictingProgramTitle()).isEqualTo("Yoga du soir");
        assertThat(first.conflictingEngagementType()).isEqualTo("SLOT");
        assertThat(first.conflictingUserProgramId()).isNull();
    }

    @Test
    void conflitDephase_horsPremiereOccurrence_doitEtreVu() {
        // LE cas central de B9 : deux séries hebdomadaires qui ne tombent pas la
        // même semaine au départ. La cible est une séance unique dans cinq
        // semaines, l'engagement est hebdomadaire : seule l'expansion le voit.
        Instant in5Weeks = nextMonday(18, 0).plus(35, ChronoUnit.DAYS);
        Schedule yoga = schedule("Yoga du soir", nextMonday(18, 0), 75, "FREQ=WEEKLY;BYDAY=MO");
        stubEngagements(List.of(userProgram(yoga)), List.of());

        Schedule unique = schedule("Atelier unique", in5Weeks.plus(30, ChronoUnit.MINUTES), 60, null);

        List<ScheduleConflictDto> conflicts = detector().detect(userId, List.of(unique));

        assertThat(conflicts)
            .as("le conflit de la semaine 5 ne doit pas passer inaperçu")
            .isNotEmpty();
        assertThat(conflicts.get(0).conflictingEngagementType()).isEqualTo("PROGRAM");
        assertThat(conflicts.get(0).conflictingUserProgramId()).isNotNull();
    }

    @Test
    void seancesQuiSeTouchent_neSontPasEnConflit() {
        // Finir à 19h15 et commencer à 19h15 est un enchaînement, pas un conflit.
        Schedule yoga = schedule("Yoga du soir", nextMonday(18, 0), 75, "FREQ=WEEKLY;BYDAY=MO");
        stubEngagements(List.of(), List.of(slotParticipation(yoga)));

        Schedule suivant = schedule("Étirements", nextMonday(19, 15), 45, "FREQ=WEEKLY;BYDAY=MO");

        assertThat(detector().detect(userId, List.of(suivant))).isEmpty();
    }

    @Test
    void joursDifferents_neSontPasEnConflit() {
        Schedule yoga = schedule("Yoga du soir", nextMonday(18, 0), 75, "FREQ=WEEKLY;BYDAY=MO");
        stubEngagements(List.of(), List.of(slotParticipation(yoga)));

        Schedule mardi = schedule("Escalade", nextMonday(18, 0).plus(1, ChronoUnit.DAYS), 60,
            "FREQ=WEEKLY;BYDAY=TU");

        assertThat(detector().detect(userId, List.of(mardi))).isEmpty();
    }

    @Test
    void sansEngagement_aucunConflit() {
        stubEngagements(List.of(), List.of());
        Schedule cible = schedule("Escalade", nextMonday(18, 30), 60, "FREQ=WEEKLY;BYDAY=MO");

        assertThat(detector().detect(userId, List.of(cible))).isEmpty();
    }

    @Test
    void memeCreneau_neSeConflictePasAvecLuiMeme() {
        // La double inscription est le refus d'un autre code (SLOT_ALREADY_JOINED),
        // pas un chevauchement.
        Schedule yoga = schedule("Yoga du soir", nextMonday(18, 0), 75, "FREQ=WEEKLY;BYDAY=MO");
        stubEngagements(List.of(), List.of(slotParticipation(yoga)));

        assertThat(detector().detect(userId, List.of(yoga))).isEmpty();
    }

    // — aides —

    private void stubEngagements(List<UserProgram> programs, List<SlotParticipation> slots) {
        when(userProgramRepository.findByUserIdAndStatus(any(), any())).thenReturn(programs);
        when(slotParticipationRepository.findByUserIdAndStatusIn(any(), anyList())).thenReturn(slots);
    }

    /** Prochain lundi à l'heure de Paris donnée, en Instant UTC. */
    private static Instant nextMonday(int hour, int minute) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Paris"));
        java.time.ZonedDateTime monday = now.plusDays(1);
        while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            monday = monday.plusDays(1);
        }
        return monday.withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant();
    }

    private static Schedule schedule(String programTitle, Instant startsAt,
                                     Integer durationMinutes, String rrule) {
        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle(programTitle);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setProgram(program);
        schedule.setPlaceName("Studio");
        schedule.setStartsAt(startsAt);
        if (durationMinutes != null) {
            schedule.setEndsAt(startsAt.plus(durationMinutes, ChronoUnit.MINUTES));
        }
        schedule.setRecurrenceRule(rrule);
        return schedule;
    }

    private UserProgram userProgram(Schedule schedule) {
        return UserProgram.builder()
            .id(UUID.randomUUID())
            .schedule(schedule)
            .status(UserProgramStatus.ACTIVE)
            .build();
    }

    private SlotParticipation slotParticipation(Schedule schedule) {
        SlotParticipation participation = new SlotParticipation();
        participation.setId(UUID.randomUUID());
        participation.setSchedule(schedule);
        participation.setStatus(ParticipationStatus.CONFIRMED);
        return participation;
    }
}
