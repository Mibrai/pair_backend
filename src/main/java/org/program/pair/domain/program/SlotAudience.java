package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * Parmi ces créneaux, lesquels comptent cette personne dans leur audience ?
     *
     * <p>Le jumeau groupé de {@link #participantIds}, pour l'unique question que
     * se posent les surfaces de lecture : « et moi, j'y suis ? ». Deux requêtes
     * pour tout un lot, là où la version unitaire en coûte deux <b>par
     * créneau</b> — sur une page de cartes-souvenirs, c'était deux allers-retours
     * transatlantiques par programme affiché.
     *
     * <p><b>La définition de l'audience n'est pas recopiée</b> : ce sont les
     * trois mêmes façons d'être inscrit, lues sur les deux mêmes dépôts, avec le
     * même filtre sur la séance pour les suiveurs de programme. Ce qui change
     * est le nombre d'appels, jamais le critère — une seconde définition se
     * manifesterait par quelqu'un vu comme inscrit sur un écran et pas sur
     * l'autre, sans qu'aucune erreur ne le dise.
     */
    public Set<UUID> slotsWhereParticipant(UUID userId, Collection<Schedule> slots) {
        if (userId == null || slots == null || slots.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Schedule> parId = new LinkedHashMap<>();
        for (Schedule slot : slots) {
            parId.put(slot.getId(), slot);
        }

        Set<UUID> resultat = new LinkedHashSet<>();

        // 1. hôte — lu sur l'arbre déjà chargé, sans requête.
        for (Schedule slot : parId.values()) {
            if (hostId(slot).anyMatch(userId::equals)) {
                resultat.add(slot.getId());
            }
        }

        // 2. rejoint directement. On part de la personne et non des créneaux :
        // ses inscriptions sont bornées par sa propre activité, et le dépôt sait
        // déjà les rendre — inutile d'ajouter une requête au dépôt pour l'autre
        // sens.
        participationRepository.findByUserIdAndStatus(userId, ParticipationStatus.CONFIRMED).stream()
            .filter(p -> p.getSchedule() != null && parId.containsKey(p.getSchedule().getId()))
            .forEach(p -> resultat.add(p.getSchedule().getId()));

        // 3. suiveur du programme, POUR CETTE SÉANCE — le filtre sur le créneau
        // est celui de programFollowerIds, et pour la même raison : un programme
        // porte plusieurs créneaux, et s'en passer ferait de tout suiveur un
        // inscrit à chacun d'eux.
        userProgramRepository.findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .filter(up -> up.getSchedule() != null && parId.containsKey(up.getSchedule().getId()))
            .forEach(up -> resultat.add(up.getSchedule().getId()));

        return resultat;
    }

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
