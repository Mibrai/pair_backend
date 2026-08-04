package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.jobs.RecurringSlotRolloverJob;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande 4 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md, au niveau du
 * modèle : le job qui maintient l'unique occurrence bookable d'un créneau
 * récurrent doit lire la RRULE, et non ajouter sept jours en aveugle.
 *
 * <p>C'est ce job qui rend corrects, sans les toucher, tous les chemins de
 * lecture qui s'appuient sur {@code starts_at} — d'où des tests sur l'état en
 * base plutôt que sur une réponse HTTP.
 *
 * <p>Aucune inscription : les organisateurs sont créés directement en base et
 * n'ont jamais besoin de jeton.
 */
class RecurringRolloverIntegrationTest extends AbstractIntegrationTest {

    @Autowired RecurringSlotRolloverJob job;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @Test
    void unCreneauHebdomadairePasse_doitEtreAvanceAUneOccurrenceFuture() {
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO", lastMonday(), Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(rolled.getStartsAt()).isAfter(Instant.now());
        assertThat(rolled.getStartsAt().atZone(ZONE).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(rolled.getStatus()).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void bydayMultiJours_doitPouvoirAtterrirSurLeSecondJour() {
        // Le cas que l'ancien job ne pouvait pas produire : en avançant de sept
        // jours, un créneau posé un lundi restait lundi pour toujours. Avec
        // MO,WE, la prochaine occurrence après un lundi passé est un mercredi.
        Instant seed = lastMonday();
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO,WE", seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(rolled.getStartsAt()).isAfter(Instant.now());
        assertThat(rolled.getStartsAt().atZone(ZONE).getDayOfWeek())
            .as("l'ancien job ne savait produire que des lundis")
            .isIn(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        // La prochaine occurrence est à moins d'une semaine : avec deux jours par
        // semaine, l'écart maximal est de quatre jours.
        assertThat(rolled.getStartsAt())
            .isBefore(Instant.now().plus(5, ChronoUnit.DAYS));
    }

    @Test
    void uneSerieCloseParUntil_doitResterPassee() {
        // L'ancien UPDATE avançait tout créneau récurrent passé, sans lire la
        // règle : il ressuscitait des séries terminées.
        Instant seed = Instant.now().minus(60, ChronoUnit.DAYS);
        String until = "UNTIL=" + ZonedDateTime.ofInstant(
                Instant.now().minus(30, ChronoUnit.DAYS), ZoneId.of("UTC"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;" + until, seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule untouched = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(untouched.getStartsAt())
            .as("une série close ne doit pas repartir dans le futur")
            .isEqualTo(seed);
    }

    @Test
    void laDureeDuCreneau_doitEtrePreservee() {
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO", lastMonday(), Duration.ofMinutes(90)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(Duration.between(rolled.getStartsAt(), rolled.getEndsAt()))
            .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void unCreneauNonRecurrentPasse_neDoitPasEtreTouche() {
        Instant seed = Instant.now().minus(3, ChronoUnit.DAYS);
        UUID_Holder holder = new UUID_Holder(createSchedule(null, seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        assertThat(scheduleRepository.findById(holder.id).orElseThrow().getStartsAt())
            .isEqualTo(seed);
    }

    // — helpers —

    /** Petit porteur d'id : les entités sont détachées entre les transactions. */
    private record UUID_Holder(java.util.UUID id) {}

    private java.util.UUID createSchedule(String rule, Instant startsAt, Duration duration) {
        User owner = userRepository.save(User.builder()
            .email("rollover-" + java.util.UUID.randomUUID() + "@pair.app")
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Rollover Host")
            .isActive(true)
            .build());

        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(owner).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Programme rollover")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle rollover")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Rollover")
            .showExactAddress(true)
            .location(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 4326)
                .createPoint(new org.locationtech.jts.geom.Coordinate(2.35, 48.85)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(duration))
            .recurrenceRule(rule)
            .maxParticipants(8)
            .isOpenToPartners(true)
            .status(SlotStatus.PAST)
            .build()).getId();
    }

    /** Le lundi le plus récent déjà passé, à 18h30 locales. */
    private Instant lastMonday() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime monday = now.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .withHour(18).withMinute(30).withSecond(0).withNano(0);
        if (!monday.toInstant().isBefore(Instant.now())) {
            monday = monday.minusWeeks(1);
        }
        return monday.toInstant();
    }
}
