package org.program.pair.domain.invitation;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.invitation.dto.InvitationDto;
import org.program.pair.domain.invitation.dto.InvitationLinkDto;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvitationController {

    private final SlotInvitationService invitationService;

    @PostMapping("/slots/{scheduleId}/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crée un lien d'invitation pour ce créneau.",
        description = "Un lien par appel, jamais réutilisé : c'est ce qui permet de "
            + "savoir laquelle de vos invitations a abouti. Réservé aux personnes du "
            + "créneau.")
    public InvitationLinkDto invite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return invitationService.invite(principal.getId(), scheduleId);
    }

    @PostMapping("/invitations/{code}/accept")
    @Operation(summary = "Accepte une invitation et rejoint le créneau.",
        description = "Les deux gestes sont faits ensemble : une invitation compte "
            + "quand elle a mis quelqu'un sur le créneau, pas quand quelqu'un a cliqué. "
            + "Un refus de rejoindre — complet, passé, organisateur bloqué — remonte tel "
            + "quel et n'enregistre rien : une invitation ne donne aucun droit de plus.")
    public SlotFeedItemDto accept(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code) {
        return invitationService.accept(principal.getId(), code);
    }

    @GetMapping("/invitations/me")
    @Operation(summary = "Mes invitations et leur statut.",
        description = "Aucun total, aucun classement : la liste dit ce que chaque "
            + "invitation est devenue, et s'arrête là.")
    public List<InvitationDto> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return invitationService.mine(principal.getId());
    }
}
