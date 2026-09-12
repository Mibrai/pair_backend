package org.program.pair.domain.watch.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEscalationService;
import org.program.pair.domain.watch.WatchSlotLifecycle;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * <p><b>Une veille armée sans contact ne quitte pas ce tableau, elle s'y arrête.</b>
 * Les rappels partent comme pour les autres — c'est l'essentiel de ce qu'elle
 * apporte — puis, à l'heure de l'escalade, elle se referme en
 * {@code NO_CONTACT} sans rien envoyer. Voir {@code WatchState.NO_CONTACT}.
 *
 * <p><b>Une action par veille et par passage.</b> Le job tourne chaque minute et
 * avance d'un cran à la fois. Si le serveur a manqué des fenêtres — arrêt,
 * redéploiement — les rappels se rattrapent sur quelques passages, et l'escalade
 * n'a lieu qu'<b>après</b> les trois rappels, jamais à leur place : c'est la
 * garantie qu'on ne saute pas les occasions de lever l'alerte soi-même.
 *
 * <p><b>Une transaction par veille, et le passage ne s'annule plus en bloc.</b>
 * {@code tick()} ne porte plus {@code @Transactional}. Il lisait auparavant les
 * entités dans sa propre transaction et rattrapait chaque {@code RuntimeException}
 * dans la boucle — ce qui ne servait à rien : {@code WatchEscalationService} est
 * {@code @Transactional} sur la classe, et une exception qui traverse son proxy,
 * ou celui d'un dépôt Spring Data, marque la transaction englobante
 * <i>rollback-only</i>. Le commit levait alors une
 * {@code UnexpectedRollbackException} et tout le passage était perdu :
 * {@code remindersSent}, états {@code ESCALATED}, événements, <b>et les lignes
 * d'outbox des autres veilles</b> — les messages aux proches de veilles saines
 * n'étaient jamais déposés. Désormais chaque veille est rechargée et avancée dans
 * sa propre transaction, et le {@code try/catch} entoure celle-ci.
 *
 * <p><b>L'idempotence tient par passage validé</b> — {@code remindersSent},
 * l'état et l'événement {@code BACKUP_ALERTED} gardent le compte, et un passage
 * annulé ne laisse aucune notification derrière lui (voir
 * {@code WatchNotificationListener}, qui n'émet qu'après le commit). Elle ne tient
 * <b>pas</b> entre deux instances : rien ne verrouille les lignes lues ici, et
 * deux instances balayant en même temps dédoubleraient rappels et demandes. Voir
 * P-BA-04 pour le verrou qui manque.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchReturnLoopJob {

    /** Profondeur du balayage : au-delà, une veille dépassée est traitée ou abandonnée. */
    private static final Duration FENETRE = Duration.ofHours(6);

    /** Les états que cette boucle avance. Relus par veille : l'état a pu changer entre-temps. */
    private static final Set<WatchState> ETATS =
        EnumSet.of(WatchState.ON_SITE, WatchState.REMINDING, WatchState.ESCALATED);

    private static final long RAPPEL_1_MIN = 15;
    private static final long RAPPEL_2_MIN = 30;
    private static final long RAPPEL_3_MIN = 45;
    private static final long ESCALADE_MIN = 60;

    private final WatchRepository watchRepository;
    private final ScheduleRepository scheduleRepository;
    private final WatchEscalationService escalation;
    private final WatchSlotLifecycle slotLifecycle;

    /**
     * {@code REQUIRED} et non {@code REQUIRES_NEW} : il n'y a plus de transaction
     * englobante à suspendre, et en tenir une par veille immobiliserait deux
     * connexions du pool au lieu d'une.
     */
    private final TransactionTemplate tx;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        Instant now = Instant.now();

        // Les identifiants seulement. La requête s'exécute dans la transaction
        // courte et en lecture seule que Spring Data ouvre pour elle, puis rend sa
        // connexion : rien ne reste ouvert pendant le passage. Une entité lue ici
        // serait détachée au moment de la modifier, d'où le rechargement plus bas.
        List<UUID> ids = watchRepository.findIdsByStateInAndDeadlineAtBetween(
            ETATS, now.minus(FENETRE), now);

        int agis = 0;
        for (UUID id : ids) {
            try {
                Boolean agi = tx.execute(s -> watchRepository.findById(id)
                    .filter(w -> ETATS.contains(w.getState()))   // relu : l'état a pu changer
                    .map(w -> avancer(w, now))
                    .orElse(false));
                if (Boolean.TRUE.equals(agi)) {
                    agis++;
                }
            } catch (RuntimeException e) {
                // Une veille qui lève n'emporte plus que la sienne. Le prochain
                // passage la reprendra ; les autres ont déjà commité.
                log.error("Boucle retour : échec sur la veille {}", id, e);
            }
        }
        if (agis > 0) {
            log.info("Boucle retour : {} veille(s) avancée(s) sur {} examinée(s)", agis, ids.size());
        }
    }

    private boolean avancer(Watch watch, Instant now) {
        long ecoule = Duration.between(watch.getDeadlineAt(), now).toMinutes();

        if (watch.getState() == WatchState.ON_SITE || watch.getState() == WatchState.REMINDING) {
            // Filet pour une annulation arrivée entre deux passages : le statut du
            // créneau est relu ici, jamais supposé. Sans lui, une séance annulée à
            // T+10 laissait partir les trois rappels puis l'alerte au proche — la
            // clôture de l'annulation n'a lieu que si quelqu'un annule, et elle ne
            // rattrape pas ce qui était déjà en vol.
            //
            // Placé dans cette branche et non en tête : une veille ESCALATED ne se
            // referme pas sur une annulation (l'alerte est sortie, elle se lève par
            // la personne), et c'est justement le seul autre état que ce balayage
            // voit.
            Schedule annule = creneauAnnuleDe(watch);
            if (annule != null) {
                log.info("Boucle retour : créneau {} annulé, la veille {} se referme sans rappel",
                    annule.getId(), watch.getId());
                return slotLifecycle.closeForCancelledSlot(annule, now) > 0;
            }

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
                if (watch.sansContact()) {
                    // Armée sans contact : les rappels ont eu lieu — c'est
                    // l'essentiel de ce qu'une telle veille apporte — et il n'y a
                    // plus rien à faire. NO_CONTACT et non ESCALATED : ce mot veut
                    // dire « un message est parti à un tiers » partout ailleurs, et
                    // le client en tire un bandeau « message d'urgence envoyé ».
                    // L'état fait aussi le travail d'un garde-fou : il sort du
                    // champ de ce balayage, qui rappellerait sinon ensureAlerted à
                    // chaque passage sur une veille sans destinataire.
                    watch.setState(WatchState.NO_CONTACT);
                    watch.setClosedAt(now);
                    escalation.inscrireCloturSansContact(watch.getId(), now);
                    return true;
                }
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

    /**
     * Le créneau de cette veille s'il est annulé, {@code null} sinon. Relu en base,
     * dans la transaction de la veille, jamais supposé. Un créneau introuvable n'est
     * pas « annulé » : on ne referme pas une veille sur une lecture qui a échoué.
     */
    private Schedule creneauAnnuleDe(Watch watch) {
        return scheduleRepository.findById(watch.getScheduleId())
            .filter(slot -> slot.getStatus() == SlotStatus.CANCELLED)
            .orElse(null);
    }
}
