package org.program.pair.domain.affiche;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.recap.SlotRecapOpenedEvent;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * « Votre affiche est prête » : à ceux qui étaient là, une fois, et pas à celui
 * qui vient de contribuer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AfficheReadyListenerTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock NotificationService notificationService;

    AfficheReadyListener listener;

    Schedule slot;
    Instant seance;

    @BeforeEach
    void setUp() {
        listener = new AfficheReadyListener(attendanceRepository, scheduleRepository, notificationService);

        seance = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Bloc du mardi");

        slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        // La ligne pointe déjà sur la séance SUIVANTE : c'est le décor d'un
        // créneau récurrent qu'un rollover vient d'avancer.
        slot.setStartsAt(seance.plus(7, ChronoUnit.DAYS));
        slot.setEndsAt(seance.plus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));

        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
    }

    @Test
    void chaquePresent_estPrevenu_saufCeluiQuiVientDeContribuer() {
        UUID contributeur = UUID.randomUUID();
        UUID autre = UUID.randomUUID();
        presents(contributeur, autre);

        listener.onRecapOpened(new SlotRecapOpenedEvent(slot.getId(), seance, contributeur));

        verify(notificationService).notify(eq(autre), eq(NotificationType.AFFICHE_READY), any());
        verify(notificationService, never()).notify(eq(contributeur), any(), any());
    }

    @Test
    void unCompteDesactive_neRecoitRien() {
        UUID contributeur = UUID.randomUUID();
        UUID parti = UUID.randomUUID();
        List<Attendance> attendances = presents(contributeur, parti);
        attendances.get(1).getUser().setIsActive(false);

        listener.onRecapOpened(new SlotRecapOpenedEvent(slot.getId(), seance, contributeur));

        verify(notificationService, never()).notify(any(UUID.class), any(), any());
    }

    /**
     * La charge décrit la <b>séance vécue</b>, pas la ligne de créneau — que le
     * rollover a déjà avancée d'une semaine. Sans cette correction, le tap
     * ouvrirait un souvenir daté de mardi prochain.
     */
    @Test
    void laChargeDecritLaSeanceVecue_pasLaLigneDeCreneau() {
        UUID contributeur = UUID.randomUUID();
        UUID autre = UUID.randomUUID();
        presents(contributeur, autre);

        listener.onRecapOpened(new SlotRecapOpenedEvent(slot.getId(), seance, contributeur));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(autre), eq(NotificationType.AFFICHE_READY), payload.capture());

        Map<String, Object> data = payload.getValue();
        assertThat(data).containsEntry("scheduleId", slot.getId().toString());
        assertThat(data).containsEntry("slotStartedAt", seance.toString());
        assertThat(data).containsEntry("sessionAt", seance.toString());
        assertThat(data).doesNotContainEntry("sessionAt", slot.getStartsAt().toString());
    }

    @Test
    void unCreneauDisparu_neFaitRienEclater() {
        when(scheduleRepository.findById(any())).thenReturn(Optional.empty());

        listener.onRecapOpened(new SlotRecapOpenedEvent(UUID.randomUUID(), seance, UUID.randomUUID()));

        verify(notificationService, never()).notify(any(UUID.class), any(), any());
    }

    private List<Attendance> presents(UUID... userIds) {
        List<Attendance> attendances = java.util.Arrays.stream(userIds).map(id -> {
            User user = new User();
            user.setId(id);
            user.setDisplayName("Participant");
            user.setIsActive(true);

            Attendance attendance = new Attendance();
            attendance.setUser(user);
            attendance.setSchedule(slot);
            attendance.setAttendedAt(seance);
            attendance.setWasPresent(true);
            return attendance;
        }).toList();

        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(slot.getId(), seance))
            .thenReturn(attendances);
        return attendances;
    }
}
