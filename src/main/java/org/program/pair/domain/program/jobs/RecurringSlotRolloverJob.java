package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Un créneau récurrent (recurrence_rule non nul) ne représente qu'une seule
 * occurrence bookable dans ce modèle de données — rien d'autre ne le fait
 * réapparaître dans le futur une fois passé. Sans ce job, un créneau
 * hebdomadaire devient PAST pour toujours après sa première occurrence, ce
 * qui a fini par vider complètement /api/slots/feed en démo (tous les
 * créneaux de seed sont récurrents).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringSlotRolloverJob {

    private final ScheduleRepository scheduleRepository;

    @Scheduled(cron = "0 30 * * * *") // Toutes les heures, à :30
    @Transactional
    public void rollPastRecurringSchedulesForward() {
        try {
            int rolled = scheduleRepository.rollRecurringSchedulesForward();
            if (rolled > 0) {
                log.info("Recurring slot rollover: {} schedule(s) advanced to their next occurrence", rolled);
            }
        } catch (Exception e) {
            log.error("Recurring slot rollover job failed", e);
        }
    }
}
