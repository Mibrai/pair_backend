package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Schedule;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
}
