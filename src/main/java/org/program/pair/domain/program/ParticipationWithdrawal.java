package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Retirer quelqu'un d'une séance <b>sans qu'il l'ait demandé</b> — et sans le dire
 * à personne d'autre qu'à la personne que la file fait entrer.
 *
 * <p><b>Pourquoi un composant.</b> Ce retrait vivait dans {@link SlotBlockEffects}.
 * La fermeture de compte ({@link AccountClosureEffects}) en a besoin à
 * l'identique : une place rendue, la file qui avance, le compteur relu, et aucun
 * message à l'organisateur qui trahirait la cause — un blocage dans un cas, une
 * fermeture de compte dans l'autre. Deux copies de ce geste finiraient par
 * différer sur l'ordre promotion-compteur ou sur le verrou, et l'écart ne se
 * verrait qu'à une place annoncée libre qui vient d'être reprise.
 *
 * <p><b>Ce n'est pas {@code SlotService.leaveSlot}</b>, et la différence est
 * voulue : le départ volontaire lève un {@code 404} quand il n'y a rien à
 * quitter, parce que l'app l'a demandé. Ici on retire ce qui existe et on passe
 * silencieusement sur ce qui n'existe plus — une participation retirée entre la
 * lecture et le verrou n'est pas une erreur.
 */
@Component
@RequiredArgsConstructor
public class ParticipationWithdrawal {

    /**
     * Les statuts de participation qu'un retrait concerne.
     *
     * <p>Les mêmes trois que {@link SlotConcernedPeople} retient, et ce n'est pas
     * une coïncidence : ce sont exactement ceux qui font qu'une séance concerne
     * quelqu'un — inscrit, intéressé, ou en attente d'une place. Un
     * {@code WITHDRAWN} n'a rien à retirer, et un statut de séance passée ne se
     * réécrit pas.
     */
    public static final Set<ParticipationStatus> A_RETIRER = EnumSet.of(
        ParticipationStatus.CONFIRMED,
        ParticipationStatus.INTERESTED,
        ParticipationStatus.WAITLISTED);

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final WaitlistPromoter waitlistPromoter;
    private final ParticipantCounter participantCounter;

    /**
     * Le même geste que {@code SlotService.leaveSlot}, verrou compris.
     *
     * <p>Le verrou est pris <b>avant</b> l'écriture, et la participation relue
     * sous lui : sans cela, deux désistements simultanés — celui-ci et un départ
     * volontaire — liraient la même file et promouvraient deux fois la même
     * personne.
     *
     * @return vrai si une participation a effectivement été retirée
     */
    public boolean withdrawParticipation(UUID scheduleId, UUID participantId) {
        Schedule slot = scheduleRepository.lockById(scheduleId).orElse(null);
        if (slot == null) {
            return false;
        }

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, participantId)
            .orElse(null);
        if (participation == null || !A_RETIRER.contains(participation.getStatus())) {
            return false;
        }

        boolean wasConfirmed = participation.getStatus() == ParticipationStatus.CONFIRMED;

        participation.setStatus(ParticipationStatus.WITHDRAWN);
        participation.setWithdrawnAt(Instant.now());
        participation.setWaitlistPosition(null);
        participationRepository.save(participation);

        // Seule une place réellement occupée se libère : un INTERESTED ou un
        // WAITLISTED n'en tenait aucune, et promouvoir derrière lui ferait entrer
        // quelqu'un sur une place qui n'existe pas. Un WAITLISTED qui part fait
        // en revanche remonter les rangs derrière lui, comme leaveWaitlist.
        if (wasConfirmed) {
            waitlistPromoter.promoteFirstWaiting(slot);
        } else {
            waitlistPromoter.resequence(scheduleId);
        }
        participantCounter.refresh(slot);
        scheduleRepository.save(slot);
        return true;
    }

    /**
     * Le même geste que {@code ProgramEnrollmentService.leaveProgram}.
     *
     * <p>Aucun motif écrit : la colonne est rendue à l'auteur du programme dans ses
     * écrans d'inscrits, et y écrire la cause lui dirait ce que le départ doit
     * taire.
     */
    public void leaveEnrollment(UserProgram enrollment, UUID scheduleId) {
        Schedule slot = scheduleRepository.lockById(scheduleId).orElse(null);
        if (slot == null) {
            return;
        }

        enrollment.setStatus(UserProgramStatus.LEFT);
        enrollment.setLeftAt(Instant.now());
        userProgramRepository.save(enrollment);

        // Une inscription active occupait bien une place, d'où la promotion sans
        // condition — comme au départ volontaire, et dans le même ordre : la place
        // est reprise avant que le compteur ne soit relu.
        waitlistPromoter.promoteFirstWaiting(slot);
        participantCounter.refresh(slot);
        scheduleRepository.save(slot);
    }
}
