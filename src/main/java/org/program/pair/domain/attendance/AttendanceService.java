package org.program.pair.domain.attendance;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.AttendanceDto;
import org.program.pair.domain.attendance.dto.PendingAttendanceDto;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.UserProgramStatus;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PracticeStatsService practiceStatsService;
    private final BadgeService badgeService;

    /**
     * Confirmation en un tap. Uniquement possible si l'utilisateur était hôte
     * ou participant confirmé (slot ou programme), et uniquement APRÈS la fin
     * du créneau.
     */
    public AttendanceDto confirm(UUID userId, UUID scheduleId, boolean wasPresent) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        Instant slotEnd = slot.getEndsAt() != null
            ? slot.getEndsAt()
            : slot.getStartsAt().plus(2, ChronoUnit.HOURS);

        if (Instant.now().isBefore(slotEnd)) {
            throw new ValidationException("Ce créneau n'est pas encore terminé.");
        }

        boolean isHost = slot.getProgram().getUserActivity().getUser().getId().equals(userId);
        boolean isSlotParticipant = participationRepository
            .existsByScheduleIdAndUserIdAndStatus(scheduleId, userId, ParticipationStatus.CONFIRMED);
        boolean isProgramParticipant = userProgramRepository
            .findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .anyMatch(up -> up.getSchedule() != null && up.getSchedule().getId().equals(scheduleId));

        if (!isHost && !isSlotParticipant && !isProgramParticipant) {
            throw new ForbiddenException("Vous n'étiez pas inscrit à ce créneau.");
        }
        if (attendanceRepository.existsByScheduleIdAndUserId(scheduleId, userId)) {
            throw new BusinessException("Présence déjà confirmée.");
        }

        Attendance attendance = new Attendance();
        attendance.setSchedule(slot);
        attendance.setUser(userRepository.getReferenceById(userId));
        attendance.setWasPresent(wasPresent);
        attendance.setAttendedAt(slot.getStartsAt());
        attendance.setConfirmedAt(Instant.now());
        attendanceRepository.save(attendance);

        if (wasPresent) {
            practiceStatsService.recalculateFor(userId);
            badgeService.evaluateBadges(userId);
        }

        return toDto(attendance);
    }

    /**
     * Personnes recommandables suite à ce créneau : celles qui ont AUSSI
     * confirmé leur présence (double confirmation), preuve d'interaction réelle.
     */
    @Transactional(readOnly = true)
    public List<UserPublicDto> getRecommendableCoParticipants(UUID userId, UUID scheduleId) {
        if (!attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(scheduleId, userId)) {
            return List.of();
        }
        return attendanceRepository.findPresentCoParticipants(scheduleId, userId).stream()
            .map(u -> userService.getPublicProfile(u.getId(), userId))
            .toList();
    }

    /**
     * Créneaux terminés (hôte ou participant confirmé) en attente de
     * confirmation de ma part.
     */
    @Transactional(readOnly = true)
    public List<PendingAttendanceDto> getPending(UUID userId) {
        List<Schedule> hosted = scheduleRepository.findHostedSchedules(userId);

        List<Schedule> slotJoined = participationRepository
            .findByUserIdAndStatus(userId, ParticipationStatus.CONFIRMED).stream()
            .map(p -> p.getSchedule())
            .toList();

        List<Schedule> programJoined = userProgramRepository
            .findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .map(up -> up.getSchedule())
            .filter(java.util.Objects::nonNull)
            .toList();

        Instant now = Instant.now();
        return java.util.stream.Stream.of(hosted, slotJoined, programJoined)
            .flatMap(List::stream)
            .distinct()
            .filter(s -> {
                Instant end = s.getEndsAt() != null ? s.getEndsAt() : s.getStartsAt().plus(2, ChronoUnit.HOURS);
                return end.isBefore(now);
            })
            .filter(s -> !attendanceRepository.existsByScheduleIdAndUserId(s.getId(), userId))
            .map(s -> new PendingAttendanceDto(
                s.getId(),
                s.getProgram().getTitle(),
                s.getPlaceName(),
                s.getStartsAt(),
                s.getEndsAt()
            ))
            .toList();
    }

    private AttendanceDto toDto(Attendance a) {
        return new AttendanceDto(
            a.getId(),
            a.getSchedule().getId(),
            a.getWasPresent(),
            a.getAttendedAt(),
            a.getConfirmedAt()
        );
    }
}
