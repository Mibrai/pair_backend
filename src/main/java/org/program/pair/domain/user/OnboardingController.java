package org.program.pair.domain.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.user.dto.AdvanceOnboardingRequest;
import org.program.pair.domain.user.dto.OnboardingStateDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping
    @Operation(summary = "Où en est le parcours d'accueil de l'appelant.")
    public OnboardingStateDto getState(@AuthenticationPrincipal UserPrincipal principal) {
        return onboardingService.getState(principal.getId());
    }

    @PatchMapping
    @Operation(summary = "Enregistre une étape franchie.",
        description = "Idempotent : rejouer une étape déjà enregistrée, ou en annoncer "
            + "une antérieure, répond 200 sans rien changer. Un parcours ne recule pas.")
    public OnboardingStateDto advance(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AdvanceOnboardingRequest request) {
        return onboardingService.advance(principal.getId(), request.step());
    }

    @PostMapping("/skip")
    @Operation(summary = "Sort du parcours sans le terminer.",
        description = "Autorisé. L'étape atteinte est conservée : c'est elle qui dit où "
            + "les gens abandonnent.")
    public OnboardingStateDto skip(@AuthenticationPrincipal UserPrincipal principal) {
        return onboardingService.skip(principal.getId());
    }
}
