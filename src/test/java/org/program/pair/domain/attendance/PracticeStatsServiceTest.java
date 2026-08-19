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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PracticeStatsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @Mock AttendanceRepository attendanceRepository;
    @Mock UserRepository userRepository;

    @Mock SlotParticipationRepository slotParticipationRepository;

    @InjectMocks
    PracticeStatsService practiceStatsService;

    @Test
    void recalculateFor_devraitTolererLaSemaineEnCoursVide() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Dernière séance : la semaine PRÉCÉDENTE (semaine en cours encore vide)
        LocalDate lastMonday = mondayOf(LocalDate.now(ZONE).minusWeeks(1));
        when(attendanceRepository.findPresentDatesDesc(userId))
            .thenReturn(List.of(toInstant(lastMonday)));
        when(attendanceRepository.countPresentByUserId(userId)).thenReturn(1);
        when(attendanceRepository.countDistinctPartners(userId)).thenReturn(1);
        when(attendanceRepository.findLastAttendanceDate(userId)).thenReturn(Optional.of(toInstant(lastMonday)));

        practiceStatsService.recalculateFor(userId);

        // Une seule semaine active (la précédente) -> streak = 1, pas 0
        assertThat(user.getCurrentStreakWeeks()).isEqualTo(1);
    }

    @Test
    void recalculateFor_devraitCasserLaSerie_apresUneSemaineSautee() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Semaine courante active + semaine sautée (il y a 2 semaines), donc streak = 1
        LocalDate thisMonday = mondayOf(LocalDate.now(ZONE));
        LocalDate twoWeeksAgo = mondayOf(LocalDate.now(ZONE).minusWeeks(2));
        when(attendanceRepository.findPresentDatesDesc(userId))
            .thenReturn(List.of(toInstant(thisMonday), toInstant(twoWeeksAgo)));
        when(attendanceRepository.countPresentByUserId(userId)).thenReturn(2);
        when(attendanceRepository.countDistinctPartners(userId)).thenReturn(1);
        when(attendanceRepository.findLastAttendanceDate(userId)).thenReturn(Optional.of(toInstant(thisMonday)));

        practiceStatsService.recalculateFor(userId);

        assertThat(user.getCurrentStreakWeeks()).isEqualTo(1);
    }

    @Test
    void recalculateFor_devraitReporterLeCompteDePartenairesDistincts() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(attendanceRepository.findPresentDatesDesc(userId)).thenReturn(List.of());
        when(attendanceRepository.countPresentByUserId(userId)).thenReturn(5);
        when(attendanceRepository.countDistinctPartners(userId)).thenReturn(3);
        when(attendanceRepository.findLastAttendanceDate(userId)).thenReturn(Optional.empty());

        practiceStatsService.recalculateFor(userId);

        assertThat(user.getAttendanceCount()).isEqualTo(5);
        assertThat(user.getDistinctPartnersCount()).isEqualTo(3);
        assertThat(user.getCurrentStreakWeeks()).isEqualTo(0);
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    private Instant toInstant(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }
}
