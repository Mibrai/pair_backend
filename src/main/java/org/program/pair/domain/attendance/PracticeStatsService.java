package org.program.pair.domain.attendance;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.ActivityBreakdownDto;
import org.program.pair.domain.attendance.dto.PracticeStatsDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Statistiques de pratique sans capteur. La métrique de valeur est le nombre
 * de partenaires différents — jamais un score comparatif, et plus de série
 * depuis le 14/09 (P-MU-25, V126) : « 5 semaines d'affilée » mesurait un effort.
 * INTERDICTION : aucun endpoint de classement/palmarès ne doit consommer ces
 * requêtes pour trier des utilisateurs entre eux.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PracticeStatsService {

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
        Instant last = attendanceRepository.findLastAttendanceDate(userId).orElse(null);

        // Recalculé et non incrémenté, comme les autres : la reconstruction est
        // idempotente et se répare toute seule, là où un +1 manqué reste faux
        // pour toujours.
        //
        // Le dénominateur compte des SÉANCES, comme le numérateur (P-BL-16). Il
        // comptait des créneaux : une série hebdomadaire suivie vingt fois pesait
        // 1 en bas et 20 en haut, si bien que le numérateur pouvait dépasser le
        // dénominateur, et les inscriptions par programme n'y entraient pas du
        // tout alors que leurs présences comptaient au-dessus. La colonne garde
        // son nom (joined_slots_count) : la renommer appartient à P-BA-13.
        user.setJoinedSlotsCount(attendanceRepository.countAnsweredByUserId(userId));
        user.setAttendanceCount(attendanceCount);
        user.setDistinctPartnersCount(distinctPartners);
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
            user.getLastAttendanceAt(),
            byActivity
        );
    }
}
