package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.RecurrenceExpander;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Un créneau récurrent (recurrence_rule non nul) ne représente qu'une seule
 * occurrence bookable dans ce modèle de données — rien d'autre ne le fait
 * réapparaître dans le futur une fois passé. Sans ce job, un créneau
 * hebdomadaire devient PAST pour toujours après sa première occurrence, ce
 * qui a fini par vider complètement /api/slots/feed en démo (tous les
 * créneaux de seed sont récurrents).
 *
 * <p>Ce job avançait {@code starts_at} de <b>sept jours en dur</b>, sans lire la
 * règle. Deux conséquences : un {@code FREQ=WEEKLY;BYDAY=MO,WE} ne tombait
 * jamais un mercredi — il restait sur le jour de sa première séance — et une
 * série {@code FREQ=MONTHLY} était purement et simplement déplacée. Il lit
 * maintenant la RRULE.
 *
 * <p>Conséquence utile : {@code starts_at} redevenant la prochaine occurrence
 * réelle, les chemins de lecture qui s'appuient dessus — {@code /slots/feed},
 * {@code /map/activities}, {@code /activities/browse},
 * {@code ProgramDto.nextSessionAt} — deviennent corrects sans rien changer chez
 * eux.
 *
 * <p><b>Fenêtre résiduelle.</b> Entre le passage d'une occurrence et l'exécution
 * suivante, le créneau reste daté dans le passé, donc vu comme sans séance à
 * venir. D'où une cadence de dix minutes plutôt que d'une heure : la fenêtre
 * pendant laquelle une activité vivante peut être prise pour expirée passe de
 * soixante à dix minutes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringSlotRolloverJob {

    private final ScheduleRepository scheduleRepository;
    private final RecurrenceExpander recurrenceExpander;

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void rollPastRecurringSchedulesForward() {
        try {
            Instant now = Instant.now();
            List<Schedule> stale = scheduleRepository.findRecurringStartedBefore(now);

            int rolled = 0;
            int exhausted = 0;
            for (Schedule schedule : stale) {
                Instant next = recurrenceExpander.nextOccurrence(
                    schedule.getStartsAt(), schedule.getRecurrenceRule(), now);

                if (next == null) {
                    // UNTIL dépassé ou COUNT épuisé : la série est finie, le
                    // créneau doit rester passé. L'avancer quand même — ce que
                    // faisait l'ancien UPDATE, qui ne lisait pas la règle —
                    // ressuscitait des séries closes.
                    exhausted++;
                    continue;
                }

                // La durée est préservée plutôt que recalculée : c'est la même
                // séance, déplacée.
                Duration duration = schedule.getEndsAt() != null
                    ? Duration.between(schedule.getStartsAt(), schedule.getEndsAt())
                    : null;

                schedule.setStartsAt(next);
                schedule.setEndsAt(duration != null ? next.plus(duration) : null);
                schedule.setStatus(SlotStatus.OPEN);
                schedule.setParticipantCount(0);
                rolled++;
            }

            if (rolled > 0 || exhausted > 0) {
                log.info("Recurring slot rollover: {} avancé(s) à leur prochaine occurrence, "
                    + "{} série(s) close(s) laissée(s) en l'état", rolled, exhausted);
            }
        } catch (Exception e) {
            log.error("Recurring slot rollover job failed", e);
        }
    }
}
