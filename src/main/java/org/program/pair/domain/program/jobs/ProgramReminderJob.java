package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rappel « votre séance commence bientôt », à T-2h.
 *
 * <p>Règle produit : à moins de deux heures du début d'un créneau d'un programme
 * où l'utilisateur est inscrit, une notification de rappel part sur son appareil.
 * Le type {@code PROGRAM_REMINDER} existait — dans l'enum, dans les trois
 * fichiers de traduction, dans les données de seed, et comme premier des quatre
 * types qui exposent {@code scheduledAt} — mais <b>aucun producteur ne l'émettait</b>.
 * Tout était en place autour d'un rappel qui ne partait jamais.
 *
 * <p><b>Pourquoi un balayage et non une planification.</b> Planifier un envoi par
 * créneau obligerait à replanifier à chaque déplacement et à annuler à chaque
 * suppression — deux opérations à n'oublier sur aucun des chemins qui touchent
 * un créneau, aujourd'hui comme dans six mois. Le balayage n'a rien à annuler ni
 * à replanifier : c'est le {@code WHERE} de
 * {@link ScheduleRepository#findDueForReminder} qui porte les trois propriétés,
 * et un chemin de modification qu'on n'aurait pas prévu se comporte correctement
 * sans le savoir. Même modèle que {@code AttendancePromptJob}, qui tourne déjà.
 *
 * <p><b>Sur le seuil.</b> Le client attend exactement deux heures : sa fenêtre
 * d'imminence vaut 2h, et un rappel doit tomber du bon côté. Un cron ne tire pas
 * à la seconde — la notification part donc <b>peu après</b> T-2h, jamais avant,
 * avec un retard borné par la cadence. D'où cinq minutes plutôt qu'une heure :
 * arriver 55 minutes après le seuil ferait d'un « rappel deux heures avant » un
 * rappel une heure avant.
 *
 * <p><b>Ce que le rappel ne fait pas.</b> Il ne double pas les préférences : le
 * filtrage {@code emailEnabled}/{@code pushEnabled} et la fréquence restent
 * l'affaire de {@code NotificationService.notify}. Un utilisateur qui a coupé
 * {@code PROGRAM_REMINDER} n'est pas notifié, et c'est le comportement voulu.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgramReminderJob {

    /**
     * Le seuil produit. Le client aligne sa fenêtre d'imminence dessus : les deux
     * valeurs doivent rester égales, sinon un rappel arrive dans un rendu qui ne
     * le traite pas comme imminent.
     */
    static final Duration REMINDER_LEAD = Duration.ofHours(2);

    private final ScheduleRepository scheduleRepository;
    private final SlotAudience slotAudience;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void sendUpcomingSlotReminders() {
        try {
            Instant now = Instant.now();
            List<Schedule> due = scheduleRepository.findDueForReminder(now, now.plus(REMINDER_LEAD));

            int notified = 0;
            for (Schedule slot : due) {
                notified += remind(slot);
                // Marqué même quand personne n'est inscrit : sans cela, un créneau
                // sans participant serait rebalayé toutes les cinq minutes jusqu'à
                // son début, pour ne rien envoyer à chaque passage.
                slot.setReminderSentFor(slot.getStartsAt());
                scheduleRepository.save(slot);
            }
            log.info("Program reminder job completed: {} slots swept, {} notifications sent",
                due.size(), notified);
        } catch (Exception e) {
            // Même posture que les autres jobs : une exécution ratée ne doit pas
            // empêcher la suivante. Les créneaux non marqués restent éligibles.
            log.error("Program reminder job failed", e);
        }
    }

    /**
     * Notifie les inscrits d'un créneau. Le payload est celui de
     * {@code ofSchedule} sans ajout : il porte déjà {@code sessionAt}, d'où
     * {@code NotificationDto} dérive {@code scheduledAt} — <b>le début du
     * créneau, pas l'heure d'envoi du rappel</b>. Les deux valeurs diffèrent de
     * deux heures, et les confondre ferait afficher « maintenant » deux heures
     * trop tôt sur un compte à rebours client.
     */
    private int remind(Schedule slot) {
        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();
        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();

        int sent = 0;
        for (UUID userId : slotAudience.participantIds(slot)) {
            // getReferenceById dans notify() ne vérifie pas l'existence : un
            // compte supprimé entre l'inscription et le rappel ferait échouer
            // l'écriture de la notification, et l'exception emporterait les
            // destinataires suivants du même créneau.
            if (!userRepository.existsById(userId)) {
                continue;
            }
            notificationService.notify(userId, hostId, NotificationType.PROGRAM_REMINDER, payload);
            sent++;
        }
        return sent;
    }
}
