package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.block.UserBlockedEvent;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un blocage fait aux inscriptions déjà prises.
 *
 * <p><b>Le défaut fermé ici.</b> Bloquer quelqu'un ne rompait que les
 * abonnements. Les deux personnes restaient inscrites l'une chez l'autre : elles
 * se retrouvaient sur le trottoir devant la salle, la liste des participants
 * portait le nom de l'une sous les yeux de l'autre, et le fil de la séance
 * rouvrait un chemin d'écriture. La porte d'entrée était bien fermée
 * ({@link SlotEntryGuard}) — mais seulement pour ceux qui n'étaient pas déjà
 * entrés.
 *
 * <p><b>Dans les deux sens</b> (décision D6) : peu importe qui a bloqué. Un
 * blocage posé par l'organisateur retire l'inscription du participant ; posé par
 * le participant, il retire la sienne tout autant. Un retrait qui dépendrait du
 * sens rendrait le blocage détectable par comparaison, et laisserait dans la
 * moitié des cas les deux personnes face à face.
 *
 * <p><b>Les séances à venir seulement.</b> La frontière est la <b>fin</b> de la
 * séance ({@link SlotTiming#endOf}), celle de « mes créneaux » et non celle de
 * l'inscription : on ne réécrit pas le passé — une séance vécue l'a été, et son
 * historique sert le signal de fiabilité et les cartes-souvenirs — mais une
 * séance en cours compte encore comme à venir, parce qu'on peut encore s'y rendre.
 *
 * <p><b>Aucune notification ne part d'ici</b>, et c'est délibéré comme dans
 * {@code BlockService} : annoncer « votre inscription a été retirée » à la
 * personne bloquée lui apprendrait le blocage à la seconde où il tombe. Elle
 * constate que le créneau a disparu de ses créneaux, comme il a disparu de son
 * fil. Seule exception, et elle ne concerne pas les deux intéressés : la personne
 * promue depuis la file d'attente est prévenue, parce qu'une place libérée est une
 * place à prendre et que rien dans cette notification ne parle du blocage.
 *
 * <p><b>Le retrait est irréversible.</b> Débloquer ne réinscrit personne — même
 * règle que pour les abonnements rompus, et pour la même raison : les faire
 * revenir supposerait de deviner qu'on regrette la décision. Une place rendue a de
 * plus pu être reprise entre-temps par la file d'attente.
 *
 * <p><b>{@code AFTER_COMMIT}, et non un appel dans la transaction du blocage.</b>
 * Le retrait fait remonter la file d'attente, ce qui notifie la personne promue ;
 * {@code notify} étant {@code @Async}, elle partirait avant le commit et
 * annoncerait une place libérée par un blocage qui peut encore échouer. Même
 * patron que {@code ScheduleChangeNotificationListener}, même raison —
 * {@code ApresCommit} la documente longuement.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlotBlockEffects {

    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;

    /** Le retrait lui-même, partagé avec la fermeture de compte. */
    private final ParticipationWithdrawal withdrawal;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserBlocked(UserBlockedEvent event) {
        try {
            withdrawCrossParticipations(event.blockerId(), event.blockedId());
        } catch (Exception e) {
            // Le blocage est enregistré et commité : il tient, et c'est lui qui
            // protège. Un retrait qui échoue laisse une inscription que la fiche
            // du créneau et « mes créneaux » masquent déjà (voir
            // {@code SlotService}), et qu'une reprise en données peut rejouer.
            // Le faire échouer ici annulerait un blocage qu'on vient d'accorder.
            log.error("Retrait des inscriptions croisées échoué entre {} et {} : {}",
                event.blockerId(), event.blockedId(), e.getMessage(), e);
        }
    }

    /**
     * Retire, dans les deux sens, ce qui inscrivait l'un aux séances à venir de
     * l'autre.
     *
     * <p>Public et appelable seul : c'est par ici que passera la reprise des
     * blocages déjà posés en base, dont les inscriptions croisées datent d'avant
     * cette règle.
     */
    public void withdrawCrossParticipations(UUID blockerId, UUID blockedId) {
        withdrawOneWay(blockerId, blockedId);
        withdrawOneWay(blockedId, blockerId);
    }

    /** Ce que {@code participantId} perd sur les séances organisées par {@code hostId}. */
    private void withdrawOneWay(UUID participantId, UUID hostId) {
        Instant now = Instant.now();

        // Les participations de la personne, puis le tri en mémoire sur
        // l'organisateur et sur la date : une personne en a quelques dizaines au
        // plus, et une requête taillée pour ce seul usage demanderait de redire en
        // SQL ce que SlotTiming décide — la convention de fin de séance vivrait
        // alors en deux langages.
        List<UUID> scheduleIds = participationRepository
            .findByUserIdAndStatusIn(participantId, List.copyOf(ParticipationWithdrawal.A_RETIRER))
            .stream()
            .map(SlotParticipation::getSchedule)
            .filter(slot -> concerne(slot, hostId, now))
            .map(Schedule::getId)
            .distinct()
            .toList();

        for (UUID scheduleId : scheduleIds) {
            if (withdrawal.withdrawParticipation(scheduleId, participantId)) {
                log.info("Blocage : participation de {} au créneau {} retirée", participantId, scheduleId);
            }
        }

        // L'autre moitié des inscriptions : un créneau peut être rejoint par la
        // porte « programme » autant que par la porte « créneau », et sa capacité
        // est partagée entre les deux (voir
        // {@code ScheduleRepository.countConfirmedParticipants}). N'en retirer
        // qu'une laisserait les deux personnes inscrites à la même séance par
        // l'autre chemin.
        for (UserProgram enrollment : userProgramRepository
                .findByUserIdAndStatus(participantId, UserProgramStatus.ACTIVE)) {
            Schedule slot = enrollment.getSchedule();
            if (slot == null || !concerne(slot, hostId, now)) {
                // Une inscription à tout un programme, sans séance désignée, n'est
                // pas une rencontre prévue : elle survit au blocage, et la porte
                // d'entrée s'occupe de la suite (SlotEntryGuard.assertNotBlocked).
                continue;
            }
            withdrawal.leaveEnrollment(enrollment, slot.getId());
            log.info("Blocage : inscription {} au programme du créneau {} quittée",
                enrollment.getId(), slot.getId());
        }
    }

    /** Ce créneau est-il une séance à venir organisée par cette personne ? */
    private static boolean concerne(Schedule slot, UUID hostId, Instant now) {
        if (slot == null || slot.getProgram() == null) {
            return false;
        }
        UUID organizerId = slot.getProgram().getUserActivity().getUser().getId();
        return hostId.equals(organizerId) && SlotTiming.endOf(slot).isAfter(now);
    }
}
