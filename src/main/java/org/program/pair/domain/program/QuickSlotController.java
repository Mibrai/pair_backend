package org.program.pair.domain.program;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quick-slots")
@RequiredArgsConstructor
public class QuickSlotController {

    private final QuickSlotService quickSlotService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publie un créneau en un seul appel.",
        description = "Déclare l'activité au profil si besoin, crée un programme au titre "
            + "auto-généré et y pose le créneau — le tout dans une transaction. La réponse "
            + "est le même objet que GET /api/slots/{scheduleId} : un seul modèle de "
            + "créneau à maintenir côté client.")
    public SlotFeedItemDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody QuickSlotRequest request) {
        return quickSlotService.create(principal.getId(), request);
    }
}
