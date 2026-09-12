package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tous ceux que cette séance concerne — la seule réponse, pour tous ceux qui la
 * posent.
 *
 * <p><b>Pourquoi un composant plutôt que trois filtres recopiés.</b> La question
 * « à qui ce créneau importe-t-il ? » se posait en trois endroits, et chacun y
 * répondait de son côté : l'annulation
 * ({@code SlotCancellationService.notifyEveryone}), la suppression
 * ({@code ProgramService.deleteSchedule}), et maintenant la modification
 * d'horaire ou de lieu. Même histoire que {@link ParticipantCounter} et
 * {@link WaitlistPromoter}, même remède — un seul endroit qui sait, appelé par
 * les trois. Un quatrième chemin qui oublierait l'une des quatre sources ne
 * produirait pas une erreur : il produirait quelqu'un qui n'est pas prévenu, ce
 * qui ne se voit qu'au déplacement pour rien.
 *
 * <p><b>Quatre sources, et il en faut quatre.</b> La capacité d'un créneau est
 * partagée entre deux mécanismes d'inscription — {@code slot_participations} et
 * {@code user_programs} — comme le dit déjà
 * {@code ScheduleRepository.countConfirmedParticipants} ; et parmi les
 * participations, trois statuts comptent :
 *
 * <ul>
 *   <li>{@code CONFIRMED} — inscrit, évidemment ;</li>
 *   <li>{@code INTERESTED} — a dit qu'il viendrait peut-être, et a donc regardé
 *       l'heure et le lieu ;</li>
 *   <li>{@code WAITLISTED} — <b>y compris</b> : quelqu'un qui attend une place a
 *       organisé sa journée autour de ce créneau autant qu'un inscrit, et ne rien
 *       lui dire le laisserait attendre une promotion vers une séance qui a
 *       changé d'heure, ou qui n'aura pas lieu.</li>
 * </ul>
 *
 * <p><b>L'ordre est conservé</b> ({@link LinkedHashSet}) : les destinataires
 * servent à composer des envois, et un ordre stable rend les journaux et les
 * tests relisibles. Un même utilisateur inscrit par les deux mécanismes
 * n'apparaît qu'une fois.
 *
 * <p>L'organisateur n'est <b>pas</b> retiré ici. C'est l'appelant qui sait s'il
 * est l'auteur du geste — une annulation par l'organisateur ne le concerne pas,
 * une annulation par la modération le concerne beaucoup — et le retirer d'office
 * rendrait ce composant incapable de répondre au second cas.
 */
@Component
@RequiredArgsConstructor
public class SlotConcernedPeople {

    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;

    /** Les statuts de participation qu'un changement sur la séance concerne. */
    private static final Set<ParticipationStatus> CONCERNES = java.util.EnumSet.of(
        ParticipationStatus.CONFIRMED,
        ParticipationStatus.INTERESTED,
        ParticipationStatus.WAITLISTED);

    /**
     * Les identifiants de ceux que cette séance concerne, sans doublon.
     *
     * <p>Deux requêtes, et pas une par personne : les deux mécanismes
     * d'inscription vivent dans deux tables, et un {@code UNION} en SQL ne
     * rendrait pas les statuts lisibles ici.
     */
    public Set<UUID> of(Schedule slot) {
        Set<UUID> concernes = new LinkedHashSet<>();

        participationRepository.findByScheduleId(slot.getId()).stream()
            .filter(p -> CONCERNES.contains(p.getStatus()))
            .map(p -> p.getUser().getId())
            .forEach(concernes::add);

        // Les inscrits au programme structuré rattachés à CE créneau, et non à
        // tout le programme : un programme hebdomadaire a plusieurs créneaux, et
        // déplacer celui de mardi ne concerne pas qui vient le jeudi.
        userProgramRepository
            .findByProgramIdAndStatus(slot.getProgram().getId(), UserProgramStatus.ACTIVE).stream()
            .filter(up -> up.getSchedule() != null && up.getSchedule().getId().equals(slot.getId()))
            .map(up -> up.getUser().getId())
            .forEach(concernes::add);

        return concernes;
    }
}
