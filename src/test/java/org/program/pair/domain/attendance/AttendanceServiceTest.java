package org.program.pair.domain.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.dto.ConfirmedAttendanceDto;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ValidationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock SlotParticipationRepository participationRepository;
    @Mock UserProgramRepository userProgramRepository;
    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock PracticeStatsService practiceStatsService;
    @Mock BadgeService badgeService;
    @Mock org.program.pair.domain.recap.SlotRecapService recapService;

    @InjectMocks
    AttendanceService attendanceService;

    @Test
    void confirm_devraitRejeter_avantLaFinDuCreneau() {
        UUID userId = UUID.randomUUID();
        Schedule slot = buildSlot(userId, Instant.now().plus(1, ChronoUnit.HOURS));
        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> attendanceService.confirm(userId, slot.getId(), true))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("pas encore terminé");
    }

    @Test
    void confirm_devraitRejeter_nonInscrit() {
        UUID hostId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        Schedule slot = buildSlot(hostId, Instant.now().minus(3, ChronoUnit.HOURS));
        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(participationRepository.existsByScheduleIdAndUserIdAndStatus(any(), any(), any())).thenReturn(false);
        when(userProgramRepository.findByUserIdAndStatus(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> attendanceService.confirm(strangerId, slot.getId(), true))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("pas inscrit");
    }

    @Test
    void confirm_devraitRejeter_doublon() {
        UUID hostId = UUID.randomUUID();
        Schedule slot = buildSlot(hostId, Instant.now().minus(3, ChronoUnit.HOURS));
        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAt(slot.getId(), hostId, slot.getStartsAt())).thenReturn(true);

        assertThatThrownBy(() -> attendanceService.confirm(hostId, slot.getId(), true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà confirmée");
    }

    @Test
    void confirm_devraitAccepter_hoteApresLaFinDuCreneau() {
        UUID hostId = UUID.randomUUID();
        Schedule slot = buildSlot(hostId, Instant.now().minus(3, ChronoUnit.HOURS));
        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAt(slot.getId(), hostId, slot.getStartsAt())).thenReturn(false);
        when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.getReferenceById(hostId)).thenReturn(slot.getProgram().getUserActivity().getUser());

        var dto = attendanceService.confirm(hostId, slot.getId(), true);

        assertThat(dto.wasPresent()).isTrue();
        assertThat(dto.scheduleId()).isEqualTo(slot.getId());
    }

    @Test
    void getRecommendableCoParticipants_devraitEtreVide_siJeNaiPasConfirmeMoiMeme() {
        UUID userId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        when(attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(scheduleId, userId)).thenReturn(false);

        List<?> result = attendanceService.getRecommendableCoParticipants(userId, scheduleId);

        assertThat(result).isEmpty();
    }


    /**
     * L'histoire se lit dans les PRÉSENCES, jamais à travers les
     * cartes-souvenirs : {@code getMine} délègue à la requête des présences
     * confirmées et ne touche pas au service des cartes. C'est toute la
     * propriété que {@code GET /api/attendances/mine} vend au client — une
     * séance sans carte doit y figurer — et elle se perdrait en silence si
     * quelqu'un rebranchait un jour cette lecture sur {@code SlotRecapService}.
     */
    @Test
    void getMine_litLesPresences_etJamaisLesCartesSouvenirs() {
        UUID userId = UUID.randomUUID();
        ConfirmedAttendanceDto entree = new ConfirmedAttendanceDto(
            UUID.randomUUID(), Instant.now().minus(3, ChronoUnit.DAYS),
            UUID.randomUUID(), "Escalade", "green-teal");
        doReturn(List.of(entree)).when(attendanceRepository).findConfirmedForUser(userId);

        assertThat(attendanceService.getMine(userId)).containsExactly(entree);

        verifyNoInteractions(recapService);
    }

    private Schedule buildSlot(UUID hostId, Instant startsAt) {
        User host = new User();
        host.setId(hostId);
        host.setDisplayName("Host");

        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").build();
        UserActivity ua = new UserActivity();
        ua.setUser(host);
        ua.setActivity(activity);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Yoga du matin");
        program.setUserActivity(ua);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setProgram(program);
        schedule.setStartsAt(startsAt);
        return schedule;
    }
}
