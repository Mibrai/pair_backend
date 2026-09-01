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
 * La boucle retour, tenue par le serveur — pas par l'application.
 *
 * <p>C'est la décision qui rend le module utile : si l'application tenait
 * l'horloge, une batterie vide donnerait zéro alerte, précisément dans le cas où
 * l'on en veut une. Côté serveur, une batterie vide donne une fausse alerte — et
 * une fausse alerte se lève, une alerte absente ne se rattrape pas.
 *
 * <p>Les jalons, comptés depuis l'échéance figée à l'armement :
 * <pre>
 *   +15 min  rappel 1        +45 min  rappel 3
 *   +30 min  rappel 2        +60 min  message ② au contact principal
 *   +75 min  message ② au contact de secours
 * </pre>
 *
 * <p><b>Une action par veille et par passage.</b> Le job tourne chaque minute et
 * avance d'un cran à la fois. Si le serveur a manqué des fenêtres — arrêt,
 * redéploiement — les rappels se rattrapent sur quelques passages, et l'escalade
 * n'a lieu qu'<b>après</b> les trois rappels, jamais à leur place : c'est la
 * garantie qu'on ne saute pas les occasions de lever l'alerte soi-même.
 *
 * <p>Chaque envoi est idempotent par construction — {@code remindersSent}, l'état,
 * et l'événement {@code BACKUP_ALERTED} gardent le compte — de sorte que deux
 * passages rapprochés, ou deux instances, ne dédoublent pas les messages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchReturnLoopJob {

    /** Profondeur du balayage : au-delà, une veille dépassée est traitée ou abandonnée. */
    private static final Duration FENETRE = Duration.ofHours(6);

    private static final long RAPPEL_1_MIN = 15;
    private static final long RAPPEL_2_MIN = 30;
    private static final long RAPPEL_3_MIN = 45;
    private static final long ESCALADE_MIN = 60;

    private final WatchRepository watchRepository;
    private final WatchEscalationService escalation;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        List<Watch> aExaminer = watchRepository.findByStateInAndDeadlineAtBetween(
            List.of(WatchState.ON_SITE, WatchState.REMINDING, WatchState.ESCALATED),
            now.minus(FENETRE), now);

        int agi = 0;
        for (Watch watch : aExaminer) {
            try {
                if (avancer(watch, now)) {
                    agi++;
                }
            } catch (RuntimeException e) {
                // Un envoi qui échoue sur une veille ne doit pas priver les autres
                // de leur tour. Le prochain passage reprendra celle-ci.
                log.error("Boucle retour : échec sur la veille {}", watch.getId(), e);
            }
        }
        if (agi > 0) {
            log.info("Boucle retour : {} veille(s) avancée(s) sur {} examinée(s)", agi, aExaminer.size());
        }
    }

    private boolean avancer(Watch watch, Instant now) {
        long ecoule = Duration.between(watch.getDeadlineAt(), now).toMinutes();

        if (watch.getState() == WatchState.ON_SITE || watch.getState() == WatchState.REMINDING) {
            int rappelsDus = ecoule >= RAPPEL_3_MIN ? 3
                : ecoule >= RAPPEL_2_MIN ? 2
                : ecoule >= RAPPEL_1_MIN ? 1 : 0;

            if (watch.getRemindersSent() < rappelsDus) {
                escalation.sendReminder(watch);
                watch.setRemindersSent(watch.getRemindersSent() + 1);
                watch.setState(WatchState.REMINDING);
                return true;
            }
            if (ecoule >= ESCALADE_MIN && watch.getRemindersSent() >= 3) {
                // On marque l'escalade ici et l'on délègue l'envoi à ensureAlerted,
                // le point unique qui prévient les contacts — le même que celui
                // qu'emprunte une clôture sous contrainte.
                watch.setState(WatchState.ESCALATED);
                escalation.ensureAlerted(watch, ecoule);
                return true;
            }
            return false;
        }

        // ESCALATED — arrivé par le minuteur ou par une clôture sous contrainte :
        // on s'assure que le contact principal a été prévenu, puis le secours à sa
        // fenêtre. ensureAlerted est idempotent et ne signale un « pas » que
        // lorsqu'il a réellement envoyé quelque chose.
        if (watch.getState() == WatchState.ESCALATED) {
            return escalation.ensureAlerted(watch, ecoule);
        }
        return false;
    }
}
