package org.program.pair.domain.attendance.jobs;

import org.program.pair.shared.observabilite.ScheduledJobMetricsAspect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotOccurrence;
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
 *
 * <p><b>Une occurrence, pas une ligne.</b> Trois défauts tenaient au même
 * raccourci — prendre la ligne {@code schedules} pour la séance qui vient d'avoir
 * lieu — et ils se cumulaient sur les créneaux récurrents :
 *
 * <ol>
 *   <li>la requête lisait {@code endsAt}, que le rollover avait déjà avancé :
 *       <b>une série ne recevait jamais aucune relance</b>. Voir
 *       {@link ScheduleRepository#findFinishedBetween} ;</li>
 *   <li>le filtre « a déjà répondu » portait sur la ligne : qui avait confirmé sa
 *       présence la première semaine n'était <b>plus jamais relancé</b>, même
 *       fenêtre corrigée. {@code AttendanceService.confirm} raisonnait déjà par
 *       occurrence de son côté ;</li>
 *   <li>le payload portait {@code startsAt} de la ligne, donc la date de la
 *       séance <b>suivante</b> : « tu y étais ? » pour un moment qui n'a pas eu
 *       lieu.</li>
 * </ol>
 *
 * <p>Et un quatrième, qui n'est pas propre aux récurrents : une fenêtre de deux
 * heures balayée toutes les heures retient chaque séance lors de deux passages.
 * Sans marqueur, un non-répondant recevait deux fois la même question — d'où
 * {@code schedules.attendance_prompted_for} (V110).
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
    private final ScheduledJobMetricsAspect metriques;
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
            Instant fromStart = from.minus(org.program.pair.domain.program.SlotTiming.DEFAULT_DURATION);
            Instant toStart = to.minus(org.program.pair.domain.program.SlotTiming.DEFAULT_DURATION);

            List<Schedule> finished = scheduleRepository.findFinishedBetween(from, to, fromStart, toStart);

            int notified = 0;
            int skipped = 0;
            for (Schedule slot : finished) {
                // De quelle séance parle-t-on ? Jamais de celle que porte la
                // ligne : sur une série, le rollover l'a déjà avancée.
                SlotOccurrence occurrence = SlotTiming.lastEndedOccurrence(slot, now);
                if (occurrence == null) {
                    continue;
                }

                // La requête présélectionne sur trois branches dont deux bornent
                // une colonne qui n'est pas forcément celle de l'occurrence
                // retenue ici (endsAt pour une série déjà avancée, par exemple).
                // C'est donc ici que la fenêtre est réellement appliquée, sur la
                // fin de la séance dont on va parler.
                if (occurrence.endsAt().isBefore(from) || occurrence.endsAt().isAfter(to)) {
                    continue;
                }

                // Idempotence au grain de l'occurrence. Deux passages successifs
                // voient la même séance — la fenêtre fait deux heures, le job
                // tourne toutes les heures — et le second ne doit rien envoyer.
                if (occurrence.startsAt().equals(slot.getAttendancePromptedFor())) {
                    skipped++;
                    continue;
                }

                for (UUID userId : unconfirmedParticipantIds(slot, occurrence)) {
                    notificationService.notify(userId,
                        slot.getProgram().getUserActivity().getUser().getId(),
                        NotificationType.ATTENDANCE_PROMPT,
                        payloadFor(slot, occurrence));
                    notified++;
                }

                // Posé même quand personne n'était à relancer : la question a été
                // posée à tout le monde qui devait l'être, et le passage suivant
                // n'a rien à reprendre. Le marqueur dit « cette occurrence a été
                // traitée », pas « un message est parti ».
                slot.setAttendancePromptedFor(occurrence.startsAt());
                scheduleRepository.save(slot);
            }
            log.info("Attendance prompt job completed: {} slots checked, {} already prompted, "
                + "{} notifications sent", finished.size(), skipped, notified);
        } catch (Exception e) {
            metriques.echecAvale(this, "promptAttendanceConfirmation");
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
            metriques.echecAvale(this, "closeUnansweredAttendanceWindows");
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
            metriques.echecAvale(this, "closeElapsedSlots");
            log.error("Close elapsed slots job failed", e);
        }
    }

    /**
     * Les inscrits du créneau, moins ceux qui ont déjà répondu <b>pour cette
     * séance-là</b>. La liste de base vient de {@link SlotAudience} — partagée
     * avec le rappel T-2h, pour que « les inscrits » ne finisse pas par vouloir
     * dire deux choses différentes selon le job qui pose la question. Le filtre
     * de présence, lui, n'appartient qu'ici : il est le sens même de cette
     * relance.
     *
     * <p><b>Par occurrence, et c'est le second défaut fermé.</b> Le filtre
     * portait sur {@code existsByScheduleIdAndUserId}, donc sur l'existence
     * d'une présence <i>quelle qu'en soit la date</i> : sur un créneau
     * hebdomadaire, avoir répondu une fois suffisait à ne plus jamais être
     * relancé. La clé est celle de {@code uq_attendance (schedule_id, user_id,
     * attended_at)} et de {@code AttendanceService.confirm}, qui écrit le début
     * de l'occurrence dans {@code attended_at} : les deux côtés posent enfin la
     * même question.
     */
    private List<UUID> unconfirmedParticipantIds(Schedule slot, SlotOccurrence occurrence) {
        List<UUID> inscrits = slotAudience.participantIds(slot);
        if (inscrits.isEmpty()) {
            return inscrits;
        }
        // Une requête pour tout le créneau, et non une par inscrit (P-BA-15).
        java.util.Set<UUID> ontRepondu = attendanceRepository.findUserIdsAyantRepondu(
            slot.getId(), occurrence.startsAt(), inscrits);
        return inscrits.stream()
            .filter(userId -> !ontRepondu.contains(userId))
            .toList();
    }

    /**
     * La charge utile, datée de la séance <b>qui vient de se terminer</b>.
     *
     * <p>{@code NotificationPayload.ofSchedule} lit la ligne, donc la séance
     * suivante sur un créneau récurrent : {@code sessionAt} — la clé que
     * {@code NotificationDto} relit pour exposer {@code scheduledAt} — annonçait
     * une date future dans une notification qui demande « tu y étais ? ». Les
     * trois clés de temps sont réécrites d'un coup pour qu'aucune ne reste sur
     * l'autre séance.
     *
     * <p>{@code occurrenceStartsAt} est ajoutée en clair : c'est l'identité de la
     * séance, celle que {@code attendances.attended_at} porte. L'app 1.1.0+16
     * l'ignore — elle route {@code ATTENDANCE_PROMPT} sur le créneau, et
     * {@code confirm} déduit l'occurrence lui-même — mais un client qui veut
     * afficher « la séance de mardi » n'a pas à la recalculer.
     */
    private static Map<String, Object> payloadFor(Schedule slot, SlotOccurrence occurrence) {
        NotificationPayload payload = NotificationPayload.ofSchedule(slot)
            .with("sessionAt", occurrence.startsAt())
            .with("startsAt", occurrence.startsAt())
            .with("occurrenceStartsAt", occurrence.startsAt());

        // La fin n'est réécrite que si le créneau en déclare une. La convention
        // des deux heures de SlotTiming sert à décider qu'une séance est
        // terminée, pas à annoncer une heure que personne n'a donnée — c'est la
        // même règle que AttendanceService.getPending applique, et ofSchedule
        // n'écrit déjà pas la clé quand endsAt est nulle.
        if (slot.getEndsAt() != null) {
            payload = payload.with("endsAt", occurrence.endsAt());
        }

        return payload.build();
    }
}
