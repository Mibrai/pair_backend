package org.program.pair.domain.watch.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEscalationService;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.repository.WatchRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * La boucle aller : « tu y es ? », puis « perdu en chemin » si personne ne répond.
 *
 * <p>Comptée depuis le début de l'occurrence, figé à l'armement :
 * <pre>
 *   +15 min  demande 1        +45 min  troisième demande sans réponse
 *   +30 min  demande 2                 ⇒ perdu en chemin
 * </pre>
 *
 * <p><b>L'étiquette « perdu en chemin » ne se pose qu'à la troisième demande.</b>
 * À quinze minutes, c'est une question — un métro en retard, une place de parking
 * — et coller une étiquette alarmante à ce moment-là serait faux. L'organisateur
 * n'est prévenu qu'à la troisième demande lui aussi : à quinze minutes, il
 * recevrait une notification pour chaque retardataire de chaque séance, et
 * couperait ses notifications.
 *
 * <p>« Je suis en chemin » repousse la base de quinze minutes, ce qui rachète
 * autant de temps avant la demande suivante. Une arrivée validée sort la veille de
 * cette boucle (elle passe {@code ON_SITE}) ; un abandon la referme.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchOutboundJob {

    private static final Duration FENETRE = Duration.ofHours(6);
    private static final long DEMANDE_1_MIN = 15;
    private static final long DEMANDE_2_MIN = 30;
    private static final long DEMANDE_3_MIN = 45;

    private final WatchRepository watchRepository;
    private final WatchEscalationService escalation;

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        List<Watch> aExaminer = watchRepository.findByStateInAndOutboundBaseAtBetween(
            List.of(WatchState.ARMED, WatchState.EN_ROUTE),
            now.minus(FENETRE), now);

        int agi = 0;
        for (Watch watch : aExaminer) {
            try {
                if (watch.getOutboundBaseAt() != null && avancer(watch, now)) {
                    agi++;
                }
            } catch (RuntimeException e) {
                log.error("Boucle aller : échec sur la veille {}", watch.getId(), e);
            }
        }
        if (agi > 0) {
            log.info("Boucle aller : {} veille(s) avancée(s) sur {} examinée(s)", agi, aExaminer.size());
        }
    }

    private boolean avancer(Watch watch, Instant now) {
        long ecoule = Duration.between(watch.getOutboundBaseAt(), now).toMinutes();

        int demandesDues = ecoule >= DEMANDE_3_MIN ? 3
            : ecoule >= DEMANDE_2_MIN ? 2
            : ecoule >= DEMANDE_1_MIN ? 1 : 0;

        if (watch.getArrivalPromptsSent() < demandesDues) {
            escalation.sendArrivalPrompt(watch);
            watch.setArrivalPromptsSent(watch.getArrivalPromptsSent() + 1);
            watch.setState(WatchState.EN_ROUTE);
            return true;
        }

        // Trois demandes passées sans arrivée : perdu en chemin. L'étiquette ne se
        // pose qu'ici, à la troisième — pas avant.
        if (ecoule >= DEMANDE_3_MIN && watch.getArrivalPromptsSent() >= 3) {
            escalation.escalateNonArrival(watch);
            return true;
        }
        return false;
    }
}
