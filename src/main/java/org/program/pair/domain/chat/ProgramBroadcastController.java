package org.program.pair.domain.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Diffusion d'un message à tous les participants d'un programme.
 *
 * <p><b>Une seule route nouvelle</b>, là où la demande en proposait deux. La
 * lecture passe par la messagerie existante : le fil apparaît dans
 * {@code GET /api/conversations} avec {@code type: "PROGRAM_BROADCAST"}, et ses
 * messages se lisent par {@code GET /api/conversations/{id}/messages} comme
 * n'importe quel autre fil. C'est la voie que la demande jugeait préférable, et
 * elle épargne au client un second modèle de messages.
 *
 * <p>Reste qu'un fil naissant à la première diffusion, il faut bien un endroit
 * pour la déclencher : c'est cette route. Les diffusions suivantes peuvent passer
 * indifféremment par ici ou par
 * {@code POST /api/conversations/{id}/messages}, qui refuse en 403 tout
 * expéditeur autre que l'auteur.
 */
@RestController
@RequiredArgsConstructor
public class ProgramBroadcastController {

    private final ChatService chatService;

    public record BroadcastRequest(
        @NotBlank @Size(max = 4000) String content
    ) {}

    @PostMapping("/api/programs/{programId}/broadcasts")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto broadcast(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody BroadcastRequest request) {
        return chatService.broadcastToProgram(principal.getId(), programId, request.content());
    }
}
