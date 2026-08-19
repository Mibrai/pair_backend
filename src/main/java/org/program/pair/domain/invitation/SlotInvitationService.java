package org.program.pair.domain.invitation;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.invitation.dto.InvitationDto;
import org.program.pair.domain.invitation.dto.InvitationLinkDto;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotInvitationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inviter quelqu'un sur un créneau, et savoir si l'invitation a abouti.
 *
 * <p>C'est la seule mesure d'acquisition qui ne passe pas par un lien anonyme :
 * ici, on sait qui a invité qui. D'où la question qui gouverne tout le lot —
 * qu'est-ce qu'on en fait ?
 *
 * <p><b>Un badge, et rien d'autre.</b> Pas de points, pas de classement de
 * parrains, pas de récompense monétaire. Ce n'est pas une omission : dès
 * qu'inviter rapporte quelque chose de quantifiable, inviter devient un
 * objectif, et les invitations cessent d'être des invitations. Le compteur
 * d'invitations converties existe en base, il alimente le badge, et
 * <b>aucun endpoint ne l'expose</b>.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SlotInvitationService {

    private final SlotInvitationRepository invitationRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final SlotAudience slotAudience;
    private final SlotService slotService;
    private final BadgeService badgeService;

    @Value("${pair.public.base-url:https://meetdo.fun}")
    private String publicBaseUrl;

    /**
     * Un lien traçable vers ce créneau.
     *
     * <p>Une invitation par appel, jamais réutilisée : c'est ce qui permet de
     * savoir laquelle a abouti. Réservé aux personnes du créneau, comme le lien
     * de partage public.
     */
    public InvitationLinkDto invite(UUID inviterId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!slotAudience.participantIds(slot).contains(inviterId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        User inviter = userRepository.findById(inviterId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        SlotInvitation invitation = invitationRepository.save(SlotInvitation.builder()
            .inviter(inviter)
            .schedule(slot)
            .inviteCode(ShareToken.nextUnique(invitationRepository::existsByInviteCode))
            .build());

        return new InvitationLinkDto(
            invitation.getInviteCode(),
            publicBaseUrl + "/i/" + invitation.getInviteCode());
    }

    /**
     * L'invité accepte : il rejoint le créneau, et l'invitation est marquée.
     *
     * <p>Les deux gestes sont faits ensemble, dans une transaction. Les séparer
     * aurait laissé des invitations « acceptées » sans participation, c'est-à-dire
     * un chiffre qui ne veut rien dire — une invitation compte quand elle a mis
     * quelqu'un sur le créneau, pas quand quelqu'un a cliqué.
     *
     * <p>Le refus de rejoindre — créneau complet, déjà passé, organisateur
     * bloqué — remonte tel quel et n'enregistre rien : c'est le même refus que
     * par la porte normale, et une invitation ne donne aucun droit
     * supplémentaire.
     */
    public SlotFeedItemDto accept(UUID inviteeId, String inviteCode) {
        SlotInvitation invitation = invitationRepository.findByInviteCode(inviteCode)
            .orElseThrow(() -> new ResourceNotFoundException("Invitation introuvable."));

        if (invitation.getSchedule() == null) {
            // Le créneau a été supprimé ; la ligne survit pour la trace, mais il
            // n'y a plus rien à rejoindre.
            throw new ResourceNotFoundException("Ce créneau n'existe plus.");
        }

        if (invitation.getInviter().getId().equals(inviteeId)) {
            throw new ValidationException("On ne peut pas accepter sa propre invitation.");
        }

        if (invitation.getConvertedAt() != null) {
            throw new ValidationException("Cette invitation a déjà été utilisée.");
        }

        User invitee = userRepository.findById(inviteeId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        // Rejoindre d'abord : si le créneau refuse, rien n'est enregistré.
        SlotFeedItemDto slot = slotService.joinSlot(
            inviteeId, invitation.getSchedule().getId(), new JoinSlotRequest(null));

        Instant now = Instant.now();
        invitation.setInvitee(invitee);
        invitation.setConvertedAt(now);

        // joinedAt ne marque pas l'acceptation mais le recrutement : le compte de
        // l'invité est-il postérieur à l'invitation ? Une invitation acceptée par
        // quelqu'un qui était déjà là a marché sans faire venir personne, et
        // confondre les deux fausserait toute mesure d'acquisition.
        if (invitee.getCreatedAt() != null
                && invitee.getCreatedAt().isAfter(invitation.getCreatedAt())) {
            invitation.setJoinedAt(now);
        }

        invitationRepository.save(invitation);

        // Le badge est évalué maintenant, pour l'inviteur. Le point d'entrée est
        // le même que partout ailleurs dans le dépôt : evaluateBadges décide,
        // ce service ne fait que dire quand regarder.
        badgeService.evaluateBadges(invitation.getInviter().getId());

        return slot;
    }

    @Transactional(readOnly = true)
    public List<InvitationDto> mine(UUID inviterId) {
        return invitationRepository.findByInviterId(inviterId).stream()
            .map(this::toDto)
            .toList();
    }

    private InvitationDto toDto(SlotInvitation invitation) {
        Schedule slot = invitation.getSchedule();
        return new InvitationDto(
            invitation.getInviteCode(),
            publicBaseUrl + "/i/" + invitation.getInviteCode(),
            slot != null ? slot.getId() : null,
            slot != null ? slot.getProgram().getTitle() : null,
            slot != null ? slot.getStartsAt() : null,
            invitation.getCreatedAt(),
            invitation.getConvertedAt() != null,
            invitation.getJoinedAt() != null);
    }
}
