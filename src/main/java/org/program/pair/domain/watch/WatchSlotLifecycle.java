package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ce que devient une veille quand la séance qu'elle surveille change de nature.
 *
 * <p><b>Le défaut que ce service referme.</b> L'échéance d'une veille est figée à
 * l'armement — {@code SlotTiming.endOf(slot) + 1 h} — et rien, jusqu'ici, ne
 * reliait cette échéance au sort du créneau. Une séance annulée laissait donc sa
 * veille armée, et la boucle retour faisait son travail à l'heure dite : trois
 * rappels à quelqu'un qui n'est jamais parti, puis un message d'alerte à son
 * proche. Une fausse alerte au proche est exactement ce que ce module existe pour
 * ne pas produire.
 *
 * <p><b>Deux gestes, une même cause.</b> {@link #closeForCancelledSlot} referme
 * ce qui n'aura pas lieu ; {@link #shiftForUpdatedSlot} déplace ce qui a bougé.
 * L'un et l'autre existent parce que l'échéance est figée à l'armement : sans
 * eux, la boucle retour travaille sur un horaire que le créneau n'a plus.
 *
 * <p><b>Pourquoi {@code CLOSED} et {@code ABANDONED}.</b> Il n'existe pas d'état
 * « annulée », et en créer un obligerait l'application à connaître une valeur
 * qu'elle ne connaît pas — son analyseur rend {@code null} sur un type
 * d'événement inconnu. {@code CLOSED} avec {@code ABANDONED} est le couple qu'une
 * séance à laquelle on renonce produit déjà, que le client sait rendre depuis la
 * 1.1.0+16, et qui dit la vérité : il n'y a plus rien à surveiller. Le libellé
 * « séance annulée, veille refermée » est une affaire de client, qui a le statut
 * du créneau sous la main.
 *
 * <p><b>Les veilles {@code ESCALATED} ne sont pas touchées.</b> Une alerte déjà
 * sortie reste à lever par la personne : elle a peut-être un vrai souci — une
 * séance annulée n'empêche personne d'être parti quelque part — et son contact ne
 * doit pas rester sans nouvelle. Refermer d'office effacerait l'alerte du côté
 * serveur sans jamais envoyer la levée.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchSlotLifecycle {

    /**
     * Les états qu'une annulation referme : tout ce qui est vivant <b>sauf</b>
     * {@code ESCALATED}. Les terminaux n'ont rien à faire ici — ils ne bougent
     * plus — et {@code ESCALATED} est exclu pour la raison dite plus haut.
     */
    static final Set<WatchState> A_REFERMER = EnumSet.of(
        WatchState.ARMED, WatchState.EN_ROUTE, WatchState.ON_SITE, WatchState.REMINDING);

    private final WatchRepository watchRepository;
    private final WatchEventRepository eventRepository;

    /**
     * Referme les veilles d'une séance annulée, sans rien envoyer à personne.
     *
     * <p><b>Aucun message ne part</b>, et c'est la décision : les inscrits sont
     * déjà prévenus de l'annulation par {@code SLOT_CANCELLED}, et un proche qui
     * recevrait quoi que ce soit ici apprendrait l'existence d'une veille dont il
     * n'a jamais été question — le lien d'urgence naît à l'alerte, pas avant.
     *
     * <p>L'événement {@code ABANDONED} est inscrit dans la même transaction que le
     * changement d'état : la chronologie est une preuve, et une veille qui se
     * refermerait sans trace ne pourrait plus être relue après coup.
     *
     * <p>Rejoint la transaction de l'appelant quand il en a une —
     * {@code SlotCancellationService.cancel}, la branche « annuler » de
     * {@code deleteSchedule}, ou la transaction d'une seule veille dans les
     * boucles. Un seul créneau à la fois : le nombre de veilles concernées se
     * compte sur les doigts d'une main.
     *
     * @return combien de veilles ont été refermées
     */
    @Transactional
    public int closeForCancelledSlot(Schedule slot, Instant now) {
        List<Watch> aReferm = watchRepository.findByScheduleIdAndStateIn(slot.getId(), A_REFERMER);
        if (aReferm.isEmpty()) {
            return 0;
        }

        for (Watch watch : aReferm) {
            watch.setState(WatchState.CLOSED);
            watch.setClosedAt(now);
            // Les entités sont gérées par la transaction courante : le changement
            // part au flush, comme partout ailleurs dans ce module.
            eventRepository.save(new WatchEvent(watch.getId(), WatchEventType.ABANDONED, now));
        }

        log.info("Créneau {} annulé : {} veille(s) refermée(s), rien envoyé à personne",
            slot.getId(), aReferm.size());
        return aReferm.size();
    }

    /**
     * Referme les veilles <b>de la personne</b> dont le compte se ferme, sans rien
     * envoyer à personne.
     *
     * <p><b>Le défaut fermé ici.</b> Fermer son compte retire ses inscriptions et
     * annule ses créneaux ; les veilles de ses propres créneaux se referment avec
     * l'annulation, mais celles qu'elle avait armées pour aller chez les autres
     * restaient vivantes. La boucle retour envoyait alors ses rappels à un compte
     * qui ne peut plus répondre — ses appareils sont détachés — puis alertait son
     * proche pour une séance où elle n'irait pas.
     *
     * <p><b>Toutes ses veilles vivantes, pas seulement celles des séances
     * quittées</b> : un compte fermé ne peut plus lever aucune veille, quelle que
     * soit la séance qu'elle surveille. Même couple {@code CLOSED} +
     * {@code ABANDONED} qu'à l'annulation, et même exclusion : une veille
     * {@code ESCALATED} a déjà prévenu un proche, qui ne doit pas rester sans
     * nouvelle parce que le serveur l'a refermée d'office.
     *
     * @return combien de veilles ont été refermées
     */
    @Transactional
    public int closeForClosedAccount(UUID userId, Instant now) {
        List<Watch> aReferm = watchRepository
            .findByUserIdAndStateNotInOrderByArmedAtDesc(userId, WatchState.TERMINAUX).stream()
            .filter(watch -> A_REFERMER.contains(watch.getState()))
            .toList();
        if (aReferm.isEmpty()) {
            return 0;
        }

        for (Watch watch : aReferm) {
            watch.setState(WatchState.CLOSED);
            watch.setClosedAt(now);
            eventRepository.save(new WatchEvent(watch.getId(), WatchEventType.ABANDONED, now));
        }

        log.info("Compte {} fermé : {} veille(s) refermée(s), rien envoyé à personne",
            userId, aReferm.size());
        return aReferm.size();
    }

    /**
     * La séance a été déplacée : l'heure limite de retour de ses veilles suit.
     *
     * <p><b>Le défaut que ceci referme.</b> {@code deadlineAt},
     * {@code occurrenceStartsAt} et {@code outboundBaseAt} sont figés à
     * l'armement, et rien ne les reliait à une modification du créneau. Un
     * organisateur qui repoussait sa séance de deux heures laissait donc des
     * veilles dont l'échéance tombait <b>pendant la séance</b> : les trois rappels
     * de retour partaient à quelqu'un qui était encore sur place, puis l'alerte
     * chez son proche. Exactement la fausse alerte que
     * {@link #closeForCancelledSlot} ferme pour l'annulation, par l'autre porte.
     *
     * <p><b>Le décalage, et non un recalcul.</b> L'échéance peut avoir été saisie
     * par la personne, repoussée d'une demi-heure par un {@code SNOOZED}, et
     * {@code outboundBaseAt} avancé d'un quart d'heure par un
     * {@code STILL_COMING}. La recalculer depuis la nouvelle fin effacerait ces
     * trois gestes. On applique donc la <b>différence</b> : ce que la personne a
     * décidé garde sa position relative à la séance.
     *
     * <p><b>Deux différences distinctes</b>, et il en faut deux : le début et la
     * fin d'un créneau ne bougent pas forcément du même pas — raccourcir une
     * séance ne déplace que sa fin. {@code occurrenceStartsAt} et
     * {@code outboundBaseAt} suivent le début, {@code deadlineAt} suit la fin.
     * Les deux « fins » sont prises au sens de {@link SlotTiming#endOf}, qui est
     * exactement celui dont l'échéance a été dérivée à l'armement : une séance
     * sans fin déclarée en a une par convention, et comparer une convention à un
     * {@code null} déplacerait l'échéance de n'importe quoi.
     *
     * <p><b>Seules les veilles vivantes hors {@code ESCALATED}</b> — le même
     * ensemble que la clôture, {@link #A_REFERMER}. Une veille escaladée a, par
     * construction, une échéance déjà dépassée et un message déjà parti chez un
     * proche : lui poser une échéance dans le futur ferait mentir la chronologie
     * sur l'instant où l'alerte est sortie, sans rien réparer. Elle reste à lever
     * par la personne, comme à l'annulation. <i>La fiche P-BL-04 écrit « chaque
     * veille non terminale », ce qui inclurait {@code ESCALATED} ; c'est un écart
     * assumé, pour la même raison qui l'exclut de la clôture.</i>
     *
     * <p><b>Une échéance repoussée dans le passé referme la veille</b> plutôt que
     * de la laisser vivante avec une heure limite dépassée : une séance avancée à
     * hier n'a plus de retour à surveiller. La chronologie porte alors les deux
     * lignes — le décalage, puis l'abandon — parce que les deux faits ont eu
     * lieu, et qu'une clôture sans son décalage serait illisible après coup.
     *
     * <p>Rejoint la transaction de l'appelant, {@code ProgramService.updateSchedule},
     * dont la modification et ce décalage doivent aboutir ensemble ou pas du tout.
     *
     * @param oldStart le début qu'avait la séance avant la modification
     * @param oldEnd   la fin <b>déclarée</b> qu'elle avait, {@code null} comprise :
     *                 la convention est appliquée ici, avec {@code oldStart}
     * @return combien de veilles ont été déplacées (celles refermées comprises)
     */
    @Transactional
    public int shiftForUpdatedSlot(Schedule slot, Instant oldStart, Instant oldEnd, Instant now) {
        if (oldStart == null || slot.getStartsAt() == null) {
            return 0;
        }

        Duration debut = Duration.between(oldStart, slot.getStartsAt());
        Duration fin = Duration.between(
            oldEnd != null ? oldEnd : oldStart.plus(SlotTiming.DEFAULT_DURATION),
            SlotTiming.endOf(slot));

        if (debut.isZero() && fin.isZero()) {
            return 0;
        }

        List<Watch> aDecaler = watchRepository
            .findByScheduleIdAndStateIn(slot.getId(), A_REFERMER).stream()
            .filter(watch -> concerneLOccurrence(watch, oldStart))
            .toList();

        if (aDecaler.isEmpty()) {
            return 0;
        }

        for (Watch watch : aDecaler) {
            if (watch.getOccurrenceStartsAt() != null) {
                watch.setOccurrenceStartsAt(watch.getOccurrenceStartsAt().plus(debut));
            }
            if (watch.getOutboundBaseAt() != null) {
                watch.setOutboundBaseAt(watch.getOutboundBaseAt().plus(debut));
            }
            if (watch.getDeadlineAt() != null) {
                watch.setDeadlineAt(watch.getDeadlineAt().plus(fin));
            }

            eventRepository.save(new WatchEvent(watch.getId(), WatchEventType.DEADLINE_SHIFTED,
                now, decrire(debut, fin)));

            if (watch.getDeadlineAt() != null && !watch.getDeadlineAt().isAfter(now)) {
                // La séance a été avancée au point que l'heure limite est
                // derrière nous : il n'y a plus de retour à surveiller, et
                // laisser la veille vivante ferait partir un rappel au prochain
                // passage de la boucle.
                watch.setState(WatchState.CLOSED);
                watch.setClosedAt(now);
                eventRepository.save(new WatchEvent(watch.getId(), WatchEventType.ABANDONED, now));
            }
        }

        log.info("Créneau {} déplacé ({} / {}) : {} veille(s) décalée(s)",
            slot.getId(), debut, fin, aDecaler.size());
        return aDecaler.size();
    }

    /**
     * La veille surveille-t-elle bien l'occurrence qu'on vient de déplacer ?
     *
     * <p>La question compte pour un créneau récurrent : le rollover avance la
     * ligne de semaine en semaine, et une veille armée pour la séance de la
     * semaine dernière ne doit pas voir son échéance bouger parce que celle de
     * cette semaine a changé d'heure.
     *
     * <p><b>À la seconde près, et non à l'identique.</b> Les deux instants ont
     * fait le voyage par une colonne {@code timestamptz}, dont la précision
     * n'est pas celle d'un {@code Instant} : une égalité stricte échouerait sur
     * des nanosecondes qu'aucune des deux valeurs ne porte vraiment. Deux
     * occurrences d'un même créneau sont séparées d'un jour au moins, donc une
     * tolérance d'une seconde ne peut pas en confondre deux.
     */
    private static boolean concerneLOccurrence(Watch watch, Instant oldStart) {
        Instant occurrence = watch.getOccurrenceStartsAt();
        return occurrence != null
            && Duration.between(occurrence, oldStart).abs().compareTo(Duration.ofSeconds(1)) < 0;
    }

    /** « début +2h00, fin +2h00 » — de quoi relire un décalage sans l'ancien horaire. */
    private static String decrire(Duration debut, Duration fin) {
        return "début " + signe(debut) + ", fin " + signe(fin);
    }

    private static String signe(Duration duree) {
        long minutes = duree.toMinutes();
        return String.format("%s%dh%02d", minutes < 0 ? "-" : "+",
            Math.abs(minutes) / 60, Math.abs(minutes) % 60);
    }
}
