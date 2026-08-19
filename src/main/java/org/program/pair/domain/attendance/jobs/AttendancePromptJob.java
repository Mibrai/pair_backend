package org.program.pair.domain.attendance.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
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

    /** Délai de grâce pour répondre. Au-delà, la question ne se pose plus. */
    private static final int ATTENDANCE_WINDOW_DAYS = 7;

    /**
     * Profondeur du balayage nocturne. Les fenêtres plus anciennes ont déjà été
     * fermées ; les reparcourir coûterait toute la table chaque nuit pour rien.
     */
    private static final int SCAN_DEPTH_DAYS = 30;

    private final ScheduleRepository scheduleRepository;
    private final org.program.pair.repository.SlotParticipationRepository participationRepository;
    private final AttendanceRepository attendanceRepository;
    private final SlotAudience slotAudience;
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
                    notificationService.notify(userId,
                        slot.getProgram().getUserActivity().getUser().getId(),
                        NotificationType.ATTENDANCE_PROMPT,
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
     * Referme les fenêtres de confirmation restées sans réponse.
     *
     * <p><b>Ce que cette fermeture dit, et ce qu'elle ne dit pas.</b> Elle dit
     * que le moment de répondre est passé. Elle ne dit pas que la personne était
     * absente — un silence peut vouloir dire « je n'y étais pas », « j'ai oublié
     * de répondre », ou « je n'ai jamais reçu la question », et trancher pour la
     * première hypothèse reviendrait à condamner sur un doute.
     *
     * <p>Aucune conséquence visible n'en découle : pas de notification, pas de
     * mention sur le profil, et rien qui pèse sur le signal de fiabilité — le
     * dénominateur de celui-ci ne compte que les séances où quelqu'un a
     * répondu, si bien qu'un silence retire la séance de la mesure au lieu de
     * peser contre. La fermeture ne sert qu'à rendre l'état lisible : sans elle,
     * « n'a pas répondu » et « n'a jamais été sollicité » se ressemblent, tous
     * deux étant l'absence d'une ligne.
     *
     * <p>La fenêtre de sept jours n'existait nulle part : la relance travaille
     * sur une à trois heures après la fin, et rien ne repassait ensuite.
     */
    @Scheduled(cron = "0 30 3 * * *") // Une fois par jour, la nuit
    @Transactional
    public void closeUnansweredAttendanceWindows() {
        try {
            Instant now = Instant.now();
            Instant closeBefore = now.minus(ATTENDANCE_WINDOW_DAYS, ChronoUnit.DAYS);
            // Borne basse : au-delà les fenêtres sont déjà fermées, et sans elle
            // le balayage reparcourrait tout l'historique chaque nuit.
            Instant scanFrom = closeBefore.minus(SCAN_DEPTH_DAYS, ChronoUnit.DAYS);

            List<SlotParticipation> unanswered =
                participationRepository.findUnansweredToClose(closeBefore, scanFrom);

            for (SlotParticipation participation : unanswered) {
                participation.setAttendanceClosedAt(now);
            }
            participationRepository.saveAll(unanswered);

            log.info("Attendance windows closed without answer: {}", unanswered.size());
        } catch (Exception e) {
            log.error("Close unanswered attendance windows job failed", e);
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
                if (SlotTiming.hasEndedBy(slot, now)) {
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

    /**
     * Les inscrits du créneau, moins ceux qui ont déjà répondu. La liste de base
     * vient de {@link SlotAudience} — partagée avec le rappel T-2h, pour que
     * « les inscrits » ne finisse pas par vouloir dire deux choses différentes
     * selon le job qui pose la question. Le filtre de présence, lui, n'appartient
     * qu'ici : il est le sens même de cette relance.
     */
    private List<UUID> unconfirmedParticipantIds(Schedule slot) {
        return slotAudience.participantIds(slot).stream()
            .filter(userId -> !attendanceRepository.existsByScheduleIdAndUserId(slot.getId(), userId))
            .toList();
    }
}
