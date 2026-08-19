package org.program.pair.domain.safety;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.safety.dto.SafetyShareLinkDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotSafetyShareController {

    private final SlotSafetyShareService safetyShareService;

    @PostMapping("/{scheduleId}/safety-share")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crée un lien de sécurité pour ce créneau.",
        description = "Réservé aux personnes inscrites au créneau et à son organisateur. "
            + "Un créneau auquel on n'est pas inscrit rend 404, jamais 403 : un refus "
            + "nommé confirmerait son existence.")
    public SafetyShareLinkDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return safetyShareService.create(principal.getId(), scheduleId);
    }
}
