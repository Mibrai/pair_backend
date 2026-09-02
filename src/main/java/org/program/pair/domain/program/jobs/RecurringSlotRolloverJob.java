package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.ParticipantCounter;
import org.program.pair.domain.program.RecurrenceExpander;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.SlotTiming;
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
    private final ParticipantCounter participantCounter;

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void rollPastRecurringSchedulesForward() {
        try {
            Instant now = Instant.now();
            List<Schedule> stale = scheduleRepository.findRecurringStartedBefore(now);

            int rolled = 0;
            int exhausted = 0;
            int inProgress = 0;
            for (Schedule schedule : stale) {
                // Commencé ne veut pas dire terminé. Le balayage retient tout
                // ce dont starts_at est passé — un index sur une colonne, pas
                // une expression — et c'est ici qu'on écarte les séances encore
                // en cours. Sans ce filtre, un créneau de deux heures était
                // avancé à la semaine suivante dix minutes après son début :
                // il disparaissait des écrans pendant qu'on le vivait, et la
                // confirmation de présence, qui exige une séance terminée,
                // n'avait jamais lieu d'être proposée.
                if (!SlotTiming.hasEndedBy(schedule, now)) {
                    inProgress++;
                    continue;
                }

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

                // La séance qu'on retire est inscrite avant d'être écrasée :
                // c'est le dernier instant où le système sait quel moment vient
                // de se terminer. Ce qui la décrit — présences, carte-souvenir
                // — s'y rattache ensuite par sa date de début, et cesse d'être
                // daté de la séance suivante. Voir SlotOccurrence.
                schedule.setLastOccurrenceStart(schedule.getStartsAt());
                schedule.setLastOccurrenceEnd(SlotTiming.endOf(schedule));

                schedule.setStartsAt(next);
                schedule.setEndsAt(duration != null ? next.plus(duration) : null);
                schedule.setStatus(SlotStatus.OPEN);

                // Le compteur est RECALCULÉ, plus remis à zéro.
                //
                // L'ancien setParticipantCount(0) disait « nouvelle occurrence,
                // nouvelles places » — mais il était seul à le dire : les
                // participations, elles, n'étaient pas retirées. Le créneau
                // annonçait donc zéro inscrit pendant que /participants en
                // listait un confirmé, et que myParticipationStatus valait
                // CONFIRMED chez l'intéressé. C'est l'écart relevé en production
                // le 01/09.
                //
                // Des deux lectures possibles, on retient celle que tous les
                // autres chemins appliquaient déjà : une inscription à un créneau
                // récurrent est un engagement qui tient d'une occurrence à la
                // suivante. Le compteur s'aligne donc sur les inscrits, et le
                // statut avec lui — un créneau récurrent complet le reste après
                // rollover, au lieu de rouvrir des places qui n'existent pas.
                participantCounter.refresh(schedule);
                rolled++;
            }

            if (rolled > 0 || exhausted > 0 || inProgress > 0) {
                log.info("Recurring slot rollover: {} avancé(s) à leur prochaine occurrence, "
                    + "{} série(s) close(s) laissée(s) en l'état, {} séance(s) encore en cours",
                    rolled, exhausted, inProgress);
            }
        } catch (Exception e) {
            log.error("Recurring slot rollover job failed", e);
        }
    }
}
