package org.program.pair.domain.guidelines;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.guidelines.dto.AcceptGuidelinesRequest;
import org.program.pair.domain.guidelines.dto.GuidelinesStateDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/guidelines")
@RequiredArgsConstructor
public class GuidelinesController {

    private final GuidelinesService guidelinesService;

    @GetMapping
    @Operation(summary = "Version en vigueur des règles, et ce que l'appelant a accepté.")
    public GuidelinesStateDto getState(@AuthenticationPrincipal UserPrincipal principal) {
        return guidelinesService.getState(principal.getId());
    }

    @PostMapping("/accept")
    @Operation(summary = "Enregistre l'acceptation de la version présentée.",
        description = "La version envoyée doit être celle en vigueur, sinon 400 "
            + "GUIDELINES_VERSION_MISMATCH : une application restée sur un texte ancien "
            + "doit relire l'état et réafficher le bon texte. Idempotent — réaccepter la "
            + "même version ne réécrit pas la date de la première acceptation.")
    public GuidelinesStateDto accept(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AcceptGuidelinesRequest request) {
        return guidelinesService.accept(principal.getId(), request.version());
    }
}
