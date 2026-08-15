package org.program.pair.domain.recap;

import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotOccurrence;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un créneau passé, son programme, son hôte — le décor minimal d'une
 * carte-souvenir, monté en mémoire.
 *
 * <p>Partagé par les trois classes de tests du lot pour que « un créneau
 * terminé il y a deux heures » veuille dire la même chose partout : c'est la
 * date de fin qui décide de la fenêtre de contribution, et une divergence de
 * décor la ferait passer d'un test à l'autre sans qu'on le voie.
 */
abstract class RecapTestFixtures {

    private final Map<UUID, Schedule> slots = new HashMap<>();
    private final Map<UUID, User> users = new HashMap<>();

    /** Un créneau terminé il y a {@code hoursSinceEnd} heures, avec son hôte et son programme. */
    protected Schedule endedSlot(int hoursSinceEnd) {
        Instant endsAt = Instant.now().minus(hoursSinceEnd, ChronoUnit.HOURS);

        User host = newUser("Hôte");

        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Sport");
        category.setColorRamp("orange-red");

        Activity activity = new Activity();
        activity.setId(UUID.randomUUID());
        activity.setName("Course à pied");
        activity.setCategory(category);

        UserActivity userActivity = new UserActivity();
        userActivity.setId(UUID.randomUUID());
        userActivity.setUser(host);
        userActivity.setActivity(activity);
        userActivity.setVisibleOnMap(true);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Footing du mardi");
        program.setUserActivity(userActivity);
        program.setIsPublic(true);

        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setPlaceName("Parc de la Tête d'Or");
        slot.setCity("Lyon");
        slot.setStartsAt(endsAt.minus(1, ChronoUnit.HOURS));
        slot.setEndsAt(endsAt);
        slot.setStatus(SlotStatus.PAST);
        slot.setParticipantCount(3);

        slots.put(slot.getId(), slot);
        return slot;
    }

    /** Une séance à venir, ouverte, rattachée au même programme. */
    protected Schedule futureOpenSlot(Program program, int hoursFromNow) {
        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setPlaceName("Parc de la Tête d'Or");
        slot.setStartsAt(Instant.now().plus(hoursFromNow, ChronoUnit.HOURS));
        slot.setStatus(SlotStatus.OPEN);
        slot.setParticipantCount(2);
        slot.setMaxParticipants(8);
        slots.put(slot.getId(), slot);
        return slot;
    }

    protected Schedule slotById(UUID scheduleId) {
        return slots.get(scheduleId);
    }

    protected UUID hostIdOf(Schedule slot) {
        return slot.getProgram().getUserActivity().getUser().getId();
    }

    protected User hostOf(Schedule slot) {
        return slot.getProgram().getUserActivity().getUser();
    }

    protected User newUser(String displayName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName(displayName);
        user.setIsActive(true);
        users.put(user.getId(), user);
        return user;
    }

    /** L'utilisateur connu sous cet identifiant, inventé s'il ne l'était pas. */
    protected User user(UUID userId) {
        return users.computeIfAbsent(userId, id -> {
            User user = new User();
            user.setId(id);
            user.setDisplayName("Participant");
            user.setIsActive(true);
            return user;
        });
    }

    protected UserPublicDto publicProfile(UUID userId) {
        User user = user(userId);
        return new UserPublicDto(user.getId(), user.getDisplayName(), null, null,
            "UNVERIFIED", List.of(), List.of(), false);
    }

    /**
     * Une présence confirmée sur la dernière séance terminée — pas sur la
     * ligne. Sur un créneau récurrent déjà avancé, {@code startsAt} désigne la
     * semaine prochaine : y rattacher une présence la rendrait invisible à la
     * carte du moment qu'on vient de vivre.
     */
    protected Attendance presentAttendance(Schedule slot, UUID userId) {
        SlotOccurrence ended = SlotTiming.lastEndedOccurrence(slot, Instant.now());
        return presentAttendance(slot, userId,
            ended != null ? ended : SlotTiming.currentOccurrence(slot));
    }

    protected Attendance presentAttendance(Schedule slot, UUID userId, SlotOccurrence occurrence) {
        Attendance attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        attendance.setSchedule(slot);
        attendance.setUser(user(userId));
        attendance.setWasPresent(true);
        attendance.setAttendedAt(occurrence.startsAt());
        attendance.setMemoryIsPublic(false);
        return attendance;
    }

    /** Une carte existante pour la séance que porte ce créneau, privée et vierge. */
    protected SlotRecap recapFor(Schedule slot) {
        return recapFor(slot, SlotTiming.currentOccurrence(slot));
    }

    /** Une carte existante pour une séance précise de ce créneau. */
    protected SlotRecap recapFor(Schedule slot, SlotOccurrence occurrence) {
        SlotRecap recap = new SlotRecap();
        recap.setId(UUID.randomUUID());
        recap.setSchedule(slot);
        recap.setOccurrenceStart(occurrence.startsAt());
        recap.setOccurrenceEnd(occurrence.endsAt());
        recap.setVisibility(RecapVisibility.PRIVATE);
        recap.setAttendeeCount(0);
        return recap;
    }

    /**
     * Un créneau hebdomadaire que le rollover a déjà avancé : sa ligne annonce
     * la séance de la semaine prochaine, et la séance vécue n'existe plus que
     * dans {@code lastOccurrence*}.
     *
     * <p>C'est le cas qui rendait tout le reste faux — date de carte, fenêtre
     * de contribution, confirmation de présence — et qu'aucun décor ne montait
     * jusqu'ici : les quatre programmes du compte de test n'exposaient que des
     * séances futures, ce qui donnait à croire que les séances passées étaient
     * filtrées alors qu'elles étaient écrasées.
     */
    protected Schedule rolledWeeklySlot(int hoursSinceLastSessionEnd) {
        Schedule slot = endedSlot(hoursSinceLastSessionEnd);
        slot.setRecurrenceRule("FREQ=WEEKLY");

        slot.setLastOccurrenceStart(slot.getStartsAt());
        slot.setLastOccurrenceEnd(slot.getEndsAt());

        // Ce que fait le job : la ligne repart sur l'occurrence suivante.
        slot.setStartsAt(slot.getStartsAt().plus(7, ChronoUnit.DAYS));
        slot.setEndsAt(slot.getEndsAt().plus(7, ChronoUnit.DAYS));
        slot.setStatus(SlotStatus.OPEN);
        return slot;
    }
}
