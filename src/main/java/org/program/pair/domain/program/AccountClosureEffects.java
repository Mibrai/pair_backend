package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.watch.WatchSlotLifecycle;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'une fermeture de compte fait aux séances : celles qu'on organise, celles
 * où l'on devait aller (P-BL-18).
 *
 * <p><b>Le défaut fermé ici.</b> Fermer son compte posait {@code is_active = false}
 * et rien d'autre. Depuis que le créneau d'un hôte au compte fermé est masqué
 * partout — fil, carte, « mes créneaux », fiche en {@code 404} — un inscrit voyait
 * sa séance disparaître sans un mot, et pouvait se déplacer pour rien. Côté
 * participant, la place restait prise par quelqu'un qui ne viendrait plus, et la
 * liste d'attente n'avançait pas.
 *
 * <p><b>Quatre effets, dans cet ordre</b> (décision du 14/09) :
 * <ol>
 *   <li><b>ses inscriptions aux séances des autres sont retirées</b>, par le même
 *       geste que le blocage ({@link ParticipationWithdrawal}) : la place se
 *       libère, la file avance, et l'organisateur ne reçoit rien — un départ
 *       ordinaire ne le prévient pas non plus ;</li>
 *   <li><b>ses créneaux à venir ou en cours sont annulés</b>, par
 *       {@link SlotCancellationService#cancel} et par lui seul (P-BL-19) : statut,
 *       date, auteur, veilles de la séance refermées, {@code SLOT_CANCELLED} aux
 *       inscrits et à la liste d'attente. <b>Sans motif</b> : le texte
 *       d'annulation habituel suffit, et la fermeture d'un compte ne regarde que
 *       son titulaire ;</li>
 *   <li><b>ses propres veilles vivantes sont refermées</b>, sans rien envoyer
 *       ({@link WatchSlotLifecycle#closeForClosedAccount}) ;</li>
 *   <li><b>ses programmes sont archivés</b>, comme par {@code deleteProgram} :
 *       sans notification, leurs séances ayant déjà été annulées au point 2.</li>
 * </ol>
 *
 * <p><b>Dans la transaction de la fermeture, et avant le {@code is_active =
 * false}</b> (D2). Une panne à mi-chemin annule tout : aucun compte fermé ne
 * garde de créneau ouvert, et aucun créneau n'est annulé pour un compte resté
 * ouvert. Ce qui rend cet ordre sûr est que <b>rien ne part avant le commit</b> :
 * l'annulation et la promotion depuis la file envoient après
 * ({@link EnvoiApresCommit}). Sans cela, une fermeture échouée aurait prévenu les
 * inscrits d'une annulation qui n'a pas eu lieu, et son rejeu les aurait
 * prévenus deux fois.
 *
 * <p><b>Rien ne se rouvre.</b> {@code CANCELLED} est terminal, {@code WITHDRAWN}
 * et {@code LEFT} aussi : si une réactivation du compte est un jour écrite, elle
 * trouvera ses séances annulées et ses places reprises.
 *
 * <p><b>« À venir ou en cours »</b> se lit sur la fin de la séance, comme au
 * blocage — <b>plus</b> une série récurrente qui a encore une occurrence devant
 * elle : entre la fin d'une séance et le passage suivant du rollover (dix
 * minutes), sa ligne est datée dans le passé alors que la série est vivante.
 * Une séance passée ne se réécrit pas : son historique sert la fiabilité et les
 * cartes-souvenirs.
 */
@Component
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AccountClosureEffects {

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final ProgramRepository programRepository;
    private final ParticipationWithdrawal withdrawal;
    private final SlotCancellationService slotCancellationService;
    private final WatchSlotLifecycle watchSlotLifecycle;
    private final RecurrenceExpander recurrenceExpander;

    public void apply(UUID userId) {
        Instant now = Instant.now();

        int retraits = withdrawParticipations(userId, now);
        int annulations = cancelHostedSlots(userId, now);
        int veilles = watchSlotLifecycle.closeForClosedAccount(userId, now);
        int archives = archivePrograms(userId, now);

        log.info("Fermeture du compte {} : {} inscription(s) retirée(s), {} créneau(x) annulé(s), "
            + "{} veille(s) refermée(s), {} programme(s) archivé(s)",
            userId, retraits, annulations, veilles, archives);
    }

    private int withdrawParticipations(UUID userId, Instant now) {
        int retraits = 0;

        List<UUID> scheduleIds = participationRepository
            .findByUserIdAndStatusIn(userId, List.copyOf(ParticipationWithdrawal.A_RETIRER))
            .stream()
            .map(SlotParticipation::getSchedule)
            .filter(slot -> hasSessionAhead(slot, now))
            .map(Schedule::getId)
            .distinct()
            .toList();
        for (UUID scheduleId : scheduleIds) {
            if (withdrawal.withdrawParticipation(scheduleId, userId)) {
                retraits++;
            }
        }

        // L'autre porte d'inscription : la capacité d'un créneau est partagée
        // entre slot_participations et user_programs. Une inscription à tout un
        // programme, sans séance désignée, n'occupe aucune place : elle reste,
        // comme au blocage.
        for (UserProgram enrollment : userProgramRepository
                .findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE)) {
            Schedule slot = enrollment.getSchedule();
            if (slot == null || !hasSessionAhead(slot, now)) {
                continue;
            }
            withdrawal.leaveEnrollment(enrollment, slot.getId());
            retraits++;
        }
        return retraits;
    }

    private int cancelHostedSlots(UUID userId, Instant now) {
        List<UUID> aAnnuler = scheduleRepository.findHostedSchedules(userId).stream()
            .filter(slot -> slot.getStatus() != SlotStatus.CANCELLED)
            .filter(slot -> hasSessionAhead(slot, now))
            .map(Schedule::getId)
            .toList();
        for (UUID scheduleId : aAnnuler) {
            slotCancellationService.cancel(userId, scheduleId, null);
        }
        return aAnnuler.size();
    }

    private int archivePrograms(UUID userId, Instant now) {
        List<Program> aArchiver = programRepository.findByOrganisateurId(userId).stream()
            .filter(program -> program.getStatus() != ProgramStatus.ARCHIVED)
            .toList();
        for (Program program : aArchiver) {
            program.setStatus(ProgramStatus.ARCHIVED);
            program.setArchivedAt(now);
            programRepository.save(program);
        }
        return aArchiver.size();
    }

    /** La séance est-elle encore devant nous — en cours comprise ? */
    private boolean hasSessionAhead(Schedule slot, Instant now) {
        if (slot == null || slot.getStartsAt() == null) {
            return false;
        }
        return SlotTiming.endOf(slot).isAfter(now)
            || recurrenceExpander.nextOccurrence(slot.getStartsAt(), slot.getRecurrenceRule(), now) != null;
    }
}
