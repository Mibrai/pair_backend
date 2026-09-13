package org.program.pair.domain.block;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import org.program.pair.shared.web.Pages;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.block.dto.BlockRequest;
import org.program.pair.domain.block.dto.BlockedUserDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class BlockController {


    private final BlockService blockService;

    @PostMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Bloque un utilisateur.",
        description = "Idempotent. Rompt aussitôt les abonnements qui liaient les deux "
            + "personnes, dans les deux sens. La personne bloquée n'en est pas informée : "
            + "ni notification, ni changement visible de son côté.")
    public void block(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody(required = false) BlockRequest request) {
        blockService.block(principal.getId(), userId,
            request == null ? null : request.reason());
    }

    @DeleteMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Lève un blocage.",
        description = "Idempotent. Les abonnements rompus au moment du blocage ne sont "
            + "pas rétablis.")
    public void unblock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        blockService.unblock(principal.getId(), userId);
    }

    @GetMapping("/me/blocked")
    @Operation(summary = "Les personnes que l'appelant a bloquées.")
    public Page<BlockedUserDto> listBlocked(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Parameter(schema = @Schema(maximum = "50", defaultValue = "20")) int size) {
        return blockService.listBlocked(principal.getId(),
            Pages.borne(page, size));
    }
}
