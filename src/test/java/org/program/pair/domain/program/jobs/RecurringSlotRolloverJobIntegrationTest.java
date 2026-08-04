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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un créneau récurrent qui devient PAST reste PAST pour toujours si rien ne
 * l'avance à sa prochaine occurrence — c'est exactement ce qui a vidé
 * /api/slots/feed en démo après quelques jours. Ce test insère directement un
 * créneau récurrent expiré depuis 3 semaines et vérifie que le rollover le
 * ramène dans le futur, réouvert, avec un compteur de participants remis à zéro.
 */
class RecurringSlotRolloverJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired RecurringSlotRolloverJob rolloverJob;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void rollover_devraitAvancerUnCreneauRecurrentExpire_versSaProchaineOccurrence() {
        User host = userRepository.findById(
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
        ).orElseThrow();

        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Cours hebdomadaire test rollover")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant staleStart = Instant.now().minus(21, ChronoUnit.DAYS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test rollover")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Rollover")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(staleStart)
            .endsAt(staleStart.plus(1, ChronoUnit.HOURS))
            .recurrenceRule("FREQ=WEEKLY;BYDAY=MO")
            .maxParticipants(8)
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .participantCount(3)
            .build());

        rolloverJob.rollPastRecurringSchedulesForward();

        Schedule reloaded = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(reloaded.getStartsAt()).isAfter(Instant.now());
        assertThat(reloaded.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(reloaded.getParticipantCount()).isZero();
        // Cette assertion vérifiait « un multiple de 7 jours depuis la graine »,
        // ce qui revenait à exiger que le créneau garde le jour de semaine de sa
        // PREMIÈRE séance. Or staleStart tombe un mardi et la règle dit BYDAY=MO :
        // l'ancien job, qui ajoutait sept jours en aveugle, laissait donc un
        // créneau « du lundi » sur un mardi indéfiniment. Le job lit maintenant la
        // règle — d'où la seule assertion qui ait du sens ici.
        assertThat(reloaded.getStartsAt().atZone(java.time.ZoneId.of("Europe/Paris")).getDayOfWeek())
            .as("BYDAY=MO doit donner un lundi, quel que soit le jour de la première séance")
            .isEqualTo(java.time.DayOfWeek.MONDAY);
    }
}
