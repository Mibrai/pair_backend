package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Qui « est » sur un créneau — la liste des personnes qu'un événement le
 * concernant doit atteindre.
 *
 * <p>Il y a trois façons d'être inscrit à une séance dans ce modèle, et aucune
 * ne subsume les deux autres : on peut l'<b>héberger</b> (auteur de l'activité
 * dont le programme porte le créneau), l'avoir <b>rejointe</b> directement
 * ({@link SlotParticipation} en {@code CONFIRMED}), ou suivre le
 * <b>programme</b> avec ce créneau pour séance ({@code UserProgram} en
 * {@code ACTIVE}). Un utilisateur peut relever de plusieurs à la fois.
 *
 * <p>Cette classe existe parce que la définition était écrite en un seul
 * exemplaire, à l'intérieur d'un job — et qu'un second producteur allait
 * fatalement en écrire une variante. Deux définitions divergentes de
 * « les inscrits » ne se manifestent pas par une erreur : elles se manifestent
 * par quelqu'un qui reçoit la relance de présence mais jamais le rappel, sans
 * que rien ne le signale. Même raison d'être que {@link SlotAddressVisibility}.
 *
 * <p>Ce que cette classe ne fait <b>pas</b> : filtrer. Un appelant qui ne veut
 * qu'une partie de cette audience — ceux qui n'ont pas confirmé leur présence,
 * par exemple — applique son propre critère par-dessus. Le partage porte sur
 * « qui est concerné », jamais sur « qui doit recevoir ceci ».
 */
@Component
@RequiredArgsConstructor
public class SlotAudience {

    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;

    /**
     * Les identifiants distincts des personnes inscrites au créneau, hôte
     * compris. Jamais nul ; vide si le créneau n'a ni programme ni auteur
     * chargé.
     */
    public List<UUID> participantIds(Schedule slot) {
        return Stream.of(hostId(slot), joinedIds(slot), programFollowerIds(slot))
            .flatMap(s -> s)
            .distinct()
            .toList();
    }

    private static Stream<UUID> hostId(Schedule slot) {
        Program program = slot.getProgram();
        if (program == null || program.getUserActivity() == null
            || program.getUserActivity().getUser() == null) {
            return Stream.empty();
        }
        return Stream.of(program.getUserActivity().getUser().getId());
    }

    private Stream<UUID> joinedIds(Schedule slot) {
        return participationRepository.findByScheduleId(slot.getId()).stream()
            .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED)
            .map(p -> p.getUser().getId());
    }

    /**
     * Suiveurs du programme dont ce créneau est la séance. Le filtre sur
     * {@code schedule} n'est pas cosmétique : un programme peut porter plusieurs
     * créneaux, et sans lui un rappel partirait à des gens inscrits à une tout
     * autre séance du même programme.
     */
    private Stream<UUID> programFollowerIds(Schedule slot) {
        Program program = slot.getProgram();
        if (program == null) {
            return Stream.empty();
        }
        return userProgramRepository
            .findByProgramIdAndStatus(program.getId(), UserProgramStatus.ACTIVE).stream()
            .filter(up -> up.getSchedule() != null && up.getSchedule().getId().equals(slot.getId()))
            .map(up -> up.getUser().getId());
    }
}
