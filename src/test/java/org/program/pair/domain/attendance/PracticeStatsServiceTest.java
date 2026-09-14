package org.program.pair.domain.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PracticeStatsServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock UserRepository userRepository;

    @Mock SlotParticipationRepository slotParticipationRepository;

    @InjectMocks
    PracticeStatsService practiceStatsService;

    @Test
    void recalculateFor_devraitReporterLeCompteDePartenairesDistincts() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(attendanceRepository.countPresentByUserId(userId)).thenReturn(5);
        when(attendanceRepository.countDistinctPartners(userId)).thenReturn(3);
        when(attendanceRepository.findLastAttendanceDate(userId)).thenReturn(Optional.empty());

        practiceStatsService.recalculateFor(userId);

        assertThat(user.getAttendanceCount()).isEqualTo(5);
        assertThat(user.getDistinctPartnersCount()).isEqualTo(3);
    }
}
