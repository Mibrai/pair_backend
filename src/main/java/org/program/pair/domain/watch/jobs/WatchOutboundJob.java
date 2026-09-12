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
 * <p><b>Une arrivée déclarée sort de cette boucle</b>, sans changer d'état : ni
 * demande, ni verdict de non-arrivée tant qu'elle attend sa validation. C'est ce
 * qui empêche qu'on classe « perdu en chemin » quelqu'un qui vient de dire qu'il
 * est arrivé. Le job porte donc aussi la bascule automatique du 03/09, qui valide
 * une arrivée déclarée passé {@code Watch.DELAI_VALIDATION_AUTO}.
 *
 * <p>« Je suis en chemin » repousse la base de quinze minutes, ce qui rachète
 * autant de temps avant la demande suivante. Une arrivée validée sort la veille de
 * cette boucle (elle passe {@code ON_SITE}) ; un abandon la referme.
 *
 * <p><b>Une transaction par veille</b>, exactement comme la boucle retour et pour
 * la même raison : {@code tick()} portait {@code @Transactional} et avalait les
 * exceptions de sa boucle, ce qui ne servait à rien — le proxy
 * {@code @Transactional} de {@code WatchEscalationService} avait déjà marqué la
 * transaction <i>rollback-only</i>, et le commit emportait tout le passage en
 * {@code UnexpectedRollbackException} alors que les pushs étaient parties. Voir
 * {@link WatchReturnLoopJob} pour le détail, et P-BA-04 pour le verrou qui manque
 * entre deux instances.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchOutboundJob {

    private static final Duration FENETRE = Duration.ofHours(6);
    private static final long DEMANDE_1_MIN = 15;
    private static final long DEMANDE_2_MIN = 30;
    private static final long DEMANDE_3_MIN = 45;

    /** Les états que cette boucle avance. Relus par veille : l'état a pu changer entre-temps. */
    private static final Set<WatchState> ETATS =
        EnumSet.of(WatchState.ARMED, WatchState.EN_ROUTE);

    private final WatchRepository watchRepository;
    private final ScheduleRepository scheduleRepository;
    private final WatchEscalationService escalation;
    private final WatchSlotLifecycle slotLifecycle;
    private final org.program.pair.domain.watch.WatchService watchService;

    /** Voir {@link WatchReturnLoopJob} : {@code REQUIRED} suffit, une connexion par veille. */
    private final TransactionTemplate tx;

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void tick() {
        Instant now = Instant.now();

        // La bascule automatique, d'abord et par sa propre requête. Elle ne peut
        // pas se greffer sur le balayage ci-dessous : celui-ci ne voit que les
        // veilles dont outbound_base_at est déjà passé, et quelqu'un qui arrive
        // en avance déclare son arrivée avant que sa veille n'y entre — sa
        // validation ne serait alors jamais tombée.
        //
        // Elle garde sa propre transaction (méthode d'un service @Transactional) et
        // reste avant la boucle, dans son propre try/catch : un échec de la bascule
        // ne doit pas empêcher les demandes d'arrivée de partir, ni l'inverse.
        try {
            int valides = watchService.confirmerLesArriveesEchues();
            if (valides > 0) {
                log.info("Boucle aller : {} arrivée(s) validée(s) par le délai", valides);
            }
        } catch (RuntimeException e) {
            log.error("Boucle aller : échec de la bascule automatique des arrivées", e);
        }

        List<UUID> ids = watchRepository.findIdsByStateInAndOutboundBaseAtBetween(
            ETATS, now.minus(FENETRE), now);

        int agis = 0;
        for (UUID id : ids) {
            try {
                Boolean agi = tx.execute(s -> watchRepository.findById(id)
                    .filter(w -> ETATS.contains(w.getState()))   // relu : l'état a pu changer
                    .filter(w -> w.getOutboundBaseAt() != null)
                    .map(w -> avancer(w, now))
                    .orElse(false));
                if (Boolean.TRUE.equals(agi)) {
                    agis++;
                }
            } catch (RuntimeException e) {
                log.error("Boucle aller : échec sur la veille {}", id, e);
            }
        }
        if (agis > 0) {
            log.info("Boucle aller : {} veille(s) avancée(s) sur {} examinée(s)", agis, ids.size());
        }
    }

    private boolean avancer(Watch watch, Instant now) {
        // Filet pour une annulation arrivée entre deux passages, avant tout le
        // reste : sur une séance annulée il n'y a plus d'arrivée à demander, et
        // surtout plus de « perdu en chemin » à prononcer à T+45 — ce verdict
        // journalise un incident au nom de quelqu'un qui n'avait plus nulle part
        // où aller. Le geste est celui de l'annulation : CLOSED + ABANDONED, rien
        // d'envoyé, et l'événement reste comme preuve.
        //
        // Avant le garde de l'arrivée déclarée, et non après : une veille dont
        // l'arrivée est déclarée sort de cette boucle sans rien faire, et resterait
        // donc ouverte pour toujours sur un créneau annulé.
        Schedule annule = creneauAnnuleDe(watch);
        if (annule != null) {
            log.info("Boucle aller : créneau {} annulé, la veille {} se referme sans demande",
                annule.getId(), watch.getId());
            return slotLifecycle.closeForCancelledSlot(annule, now) > 0;
        }

        // Une arrivée déclarée sort de cette boucle, et c'est structurel plutôt
        // qu'un garde-fou. Sans cela, deux choses arrivaient : les demandes
        // « tu y es ? » continuaient de partir à quelqu'un qui venait de dire
        // qu'il y était, et surtout le verdict de non-arrivée tombait à T+45 sur
        // une veille en attente de validation — terminale, donc plus de code de
        // retour possible, donc une soirée que plus rien ne surveille. C'est
        // l'inverse exact de ce que ce module existe pour faire.
        //
        // Ce qui l'attend désormais est la validation : celle de l'hôte, ou celle
        // du délai, traitée en tête de tick().
        if (watch.getArrivalClaimedAt() != null) {
            return false;
        }

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

    /**
     * Le créneau de cette veille s'il est annulé, {@code null} sinon. Même lecture
     * que dans la boucle retour : relue en base, jamais supposée, et un créneau
     * introuvable n'est pas « annulé ».
     */
    private Schedule creneauAnnuleDe(Watch watch) {
        return scheduleRepository.findById(watch.getScheduleId())
            .filter(slot -> slot.getStatus() == SlotStatus.CANCELLED)
            .orElse(null);
    }
}
