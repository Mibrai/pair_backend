package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * La file d'attente d'un créneau : qui y entre quand une place se libère, et dans
 * quel ordre les rangs se recompactent.
 *
 * <p><b>Pourquoi un composant.</b> Une place peut se libérer par deux chemins —
 * quitter le créneau ({@code SlotService.leaveSlot}) et quitter le programme
 * ({@code ProgramEnrollmentService.leaveProgram}) — parce que la capacité du
 * créneau est partagée entre les deux formes d'inscription, comme le dit déjà
 * {@code ScheduleRepository.countConfirmedParticipants}. Seul le premier faisait
 * remonter la file : une place rendue par le second restait libre pendant que
 * quelqu'un l'attendait, et « vous êtes 1er » ne devenait jamais rien. Même
 * histoire que {@link ParticipantCounter}, même remède — un seul endroit qui sait
 * faire, appelé par les deux.
 */
@Component
@RequiredArgsConstructor
public class WaitlistPromoter {

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final ScheduleConflictDetector conflictDetector;
    private final NotificationService notificationService;

    /**
     * Une place s'est libérée : la première personne de la file y entre.
     *
     * <p><b>À appeler sous le verrou pessimiste du créneau, et seulement là.</b>
     * C'est ce qui empêche deux désistements simultanés de promouvoir la même
     * personne ou de sauter un rang. Les deux appelants prennent
     * {@code lockById} avant toute écriture.
     *
     * <p><b>Le conflit d'agenda est vérifié ici, et pas à l'inscription en
     * file.</b> Attendre n'est pas s'engager : interdire de patienter sur deux
     * créneaux qui se chevauchent viderait la liste d'attente de son usage, qui
     * est précisément de garder plusieurs fers au feu. Mais promouvoir quelqu'un
     * qui s'est engagé ailleurs entre-temps créerait un double engagement qu'il
     * n'a pas choisi — on passe donc au suivant, en le laissant dans la file.
     */
    public void promoteFirstWaiting(Schedule slot) {
        if (slot.getMaxParticipants() != null
                && scheduleRepository.countConfirmedParticipants(slot.getId())
                    >= slot.getMaxParticipants()) {
            return;
        }

        for (SlotParticipation candidate : participationRepository.findWaitlist(slot.getId())) {
            UUID candidateId = candidate.getUser().getId();

            if (!conflictDetector.detect(candidateId, List.of(slot)).isEmpty()) {
                continue;
            }

            candidate.setStatus(ParticipationStatus.CONFIRMED);
            candidate.setPromotedAt(Instant.now());
            candidate.setWaitlistPosition(null);
            participationRepository.save(candidate);

            resequence(slot.getId());

            notificationService.notify(candidateId,
                slot.getProgram().getUserActivity().getUser().getId(),
                NotificationType.WAITLIST_PROMOTED,
                NotificationPayload.ofSchedule(slot).build());
            return;
        }
    }

    /**
     * Recompacte les rangs à partir de 1.
     *
     * <p>Sans cela, la file garderait des trous — deuxième, quatrième, cinquième —
     * et « vous êtes 4e » deviendrait faux dès le premier départ.
     */
    public void resequence(UUID scheduleId) {
        int position = 1;
        for (SlotParticipation waiting : participationRepository.findWaitlist(scheduleId)) {
            if (!Integer.valueOf(position).equals(waiting.getWaitlistPosition())) {
                waiting.setWaitlistPosition(position);
                participationRepository.save(waiting);
            }
            position++;
        }
    }
}
