package org.program.pair.domain.visibility;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.visibility.dto.VisibilityDtos.CutAllResponse;
import org.program.pair.domain.visibility.dto.VisibilityDtos.VisibilityStateResponse;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/visibility")
@RequiredArgsConstructor
@Tag(name = "Qui me voit", description = "L'état de chaque canal d'observation, et la coupure de tous")
@SecurityRequirement(name = "bearerAuth")
public class VisibilityController {

    private final VisibilityService visibilityService;

    @PostMapping("/cut-all")
    @Operation(summary = "Tout couper, en une transaction",
        description = "Idempotente et atomique : si elle échoue, rien n'a changé. AFFICHES : toutes les "
            + "affiches passent à NOBODY (publishedAt inchangé) et la préférence affiche.audience à "
            + "nobody. WATCH_LINKS : les liens publics encore ouvrables sont révoqués, veilles closes "
            + "depuis moins de 24 h comprises ; les veilles elles-mêmes continuent. CHAT_LOCATION : les "
            + "points non échus sont effacés, et les messages porteurs du marqueur [meetdo:pos v1 …] "
            + "envoyés depuis moins de 30 min sont supprimés, diffusés sur /queue/messages.edited. "
            + "MAP_PRESENCE : locationPublic, showLocation et showOnMap passent à false. LIVE_STATUS : "
            + "rien n'est servi aujourd'hui. Chaque coupure est journalisée (voir GET). Aucun décompte.")
    public CutAllResponse cutAll(@AuthenticationPrincipal UserPrincipal principal) {
        return visibilityService.cutAll(principal.getId());
    }

    @GetMapping
    @Operation(summary = "L'état réel de chaque canal",
        description = "Des booléens, jamais un nombre de liens ou d'affiches.")
    public VisibilityStateResponse state(@AuthenticationPrincipal UserPrincipal principal) {
        return visibilityService.state(principal.getId());
    }
}
