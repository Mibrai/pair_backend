package org.program.pair.domain.attendance;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.ActivityBreakdownDto;
import org.program.pair.domain.attendance.dto.PracticeStatsDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Statistiques de pratique sans capteur. La métrique de valeur est le nombre
 * de partenaires différents et la régularité — jamais un score comparatif.
 * INTERDICTION : aucun endpoint de classement/palmarès ne doit consommer ces
 * requêtes pour trier des utilisateurs entre eux.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PracticeStatsService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final AttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final org.program.pair.repository.SlotParticipationRepository slotParticipationRepository;

    /**
     * Recalcule les compteurs dénormalisés d'un utilisateur.
     * Appelé après chaque confirmation de présence.
     */
    public void recalculateFor(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();

        int attendanceCount = attendanceRepository.countPresentByUserId(userId);
        int distinctPartners = attendanceRepository.countDistinctPartners(userId);
        int streakWeeks = computeWeeklyStreak(userId);
        Instant last = attendanceRepository.findLastAttendanceDate(userId).orElse(null);

        // Recalculé et non incrémenté, comme les autres : la reconstruction est
        // idempotente et se répare toute seule, là où un +1 manqué reste faux
        // pour toujours.
        user.setJoinedSlotsCount(
            slotParticipationRepository.countPastJoinedByUserId(userId, Instant.now()));
        user.setAttendanceCount(attendanceCount);
        user.setDistinctPartnersCount(distinctPartners);
        user.setCurrentStreakWeeks(streakWeeks);
        user.setLastAttendanceAt(last);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public PracticeStatsDto getStats(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();

        List<ActivityBreakdownDto> byActivity = attendanceRepository.countByActivityForUser(userId).stream()
            .map(row -> new ActivityBreakdownDto(
                (UUID) row[0],
                (String) row[1],
                ((Number) row[2]).intValue()
            ))
            .toList();

        return new PracticeStatsDto(
            user.getAttendanceCount(),
            user.getDistinctPartnersCount(),
            user.getCurrentStreakWeeks(),
            user.getLastAttendanceAt(),
            byActivity
        );
    }

    /**
     * La série se compte en SEMAINES, pas en jours : une pratique quotidienne
     * obligatoire serait contre-productive et culpabilisante pour des loisirs.
     */
    private int computeWeeklyStreak(UUID userId) {
        List<Instant> dates = attendanceRepository.findPresentDatesDesc(userId);
        if (dates.isEmpty()) return 0;

        Set<LocalDate> activeWeeks = dates.stream()
            .map(i -> i.atZone(ZONE).toLocalDate().with(DayOfWeek.MONDAY))
            .collect(Collectors.toSet());

        LocalDate cursor = LocalDate.now(ZONE).with(DayOfWeek.MONDAY);
        // Tolérance : la semaine en cours peut être encore vide sans casser la série
        if (!activeWeeks.contains(cursor)) cursor = cursor.minusWeeks(1);

        int streak = 0;
        while (activeWeeks.contains(cursor)) {
            streak++;
            cursor = cursor.minusWeeks(1);
        }
        return streak;
    }
}
