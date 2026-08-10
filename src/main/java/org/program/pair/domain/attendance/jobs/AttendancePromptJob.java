package org.program.pair.domain.attendance.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.UserProgramStatus;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relance post-créneau. RÈGLE : une seule relance, jamais de rappel insistant
 * — le job ne notifie que les créneaux terminés entre 1h et 3h auparavant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttendancePromptJob {

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 * * * *") // Toutes les heures, à l'heure pile
    @Transactional
    public void promptAttendanceConfirmation() {
        log.info("Starting attendance prompt job");
        try {
            Instant now = Instant.now();
            Instant from = now.minus(3, ChronoUnit.HOURS);
            Instant to = now.minus(1, ChronoUnit.HOURS);
            // Pour les créneaux sans endsAt, la fin est conventionnellement startsAt + 2h
            // (voir AttendanceService.confirm) : on décale la fenêtre de recherche d'autant.
            Instant fromStart = from.minus(2, ChronoUnit.HOURS);
            Instant toStart = to.minus(2, ChronoUnit.HOURS);

            List<Schedule> finished = scheduleRepository.findFinishedBetween(from, to, fromStart, toStart);

            int notified = 0;
            for (Schedule slot : finished) {
                for (UUID userId : unconfirmedParticipantIds(slot)) {
                    notificationService.notify(userId, NotificationType.ATTENDANCE_PROMPT,
                        NotificationPayload.ofSchedule(slot).build());
                    notified++;
                }
            }
            log.info("Attendance prompt job completed: {} slots checked, {} notifications sent",
                finished.size(), notified);
        } catch (Exception e) {
            log.error("Attendance prompt job failed", e);
        }
    }

    /**
     * Fait avancer les créneaux OPEN/FULL dont la fin est passée vers PAST,
     * pour que le statut affiché reste fidèle à la réalité.
     */
    @Scheduled(cron = "0 15 * * * *") // Toutes les heures, à :15
    @Transactional
    public void closeElapsedSlots() {
        try {
            Instant now = Instant.now();
            List<Schedule> candidates = scheduleRepository.findOpenOrFullStartedBefore(
                now.minus(2, ChronoUnit.HOURS));

            int closed = 0;
            for (Schedule slot : candidates) {
                Instant end = slot.getEndsAt() != null ? slot.getEndsAt() : slot.getStartsAt().plus(2, ChronoUnit.HOURS);
                if (end.isBefore(now)) {
                    slot.setStatus(SlotStatus.PAST);
                    scheduleRepository.save(slot);
                    closed++;
                }
            }
            log.info("Close elapsed slots job completed: {} slots marked PAST", closed);
        } catch (Exception e) {
            log.error("Close elapsed slots job failed", e);
        }
    }

    private List<UUID> unconfirmedParticipantIds(Schedule slot) {
        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();

        return java.util.stream.Stream.of(
                java.util.stream.Stream.of(hostId),
                participationRepository.findByScheduleId(slot.getId()).stream()
                    .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED)
                    .map(p -> p.getUser().getId()),
                userProgramRepository.findByProgramIdAndStatus(slot.getProgram().getId(), UserProgramStatus.ACTIVE)
                    .stream()
                    .filter(up -> up.getSchedule() != null && up.getSchedule().getId().equals(slot.getId()))
                    .map(up -> up.getUser().getId())
            )
            .flatMap(s -> s)
            .distinct()
            .filter(userId -> !attendanceRepository.existsByScheduleIdAndUserId(slot.getId(), userId))
            .toList();
    }
}
