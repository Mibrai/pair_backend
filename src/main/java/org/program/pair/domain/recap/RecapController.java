package org.program.pair.domain.recap;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.recap.dto.RecapFeedRequest;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Les cartes-souvenirs vues en liste : le fil autour de moi, et les miennes.
 */
@RestController
@RequestMapping("/api/recaps")
@RequiredArgsConstructor
@Validated
public class RecapController {

    private final SlotRecapService recapService;

    @GetMapping("/feed")
    @Operation(summary = "Les cartes publiques autour de moi",
        description = "Mêmes paramètres et mêmes bornes que GET /api/slots/feed, pour que "
            + "le client réutilise la position et le rayon qu'il partage déjà. Rend un "
            + "tableau nu.")
    public List<SlotRecapDto> feed(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute RecapFeedRequest request) {
        return recapService.getFeed(request, principal.getId());
    }

    @GetMapping("/mine")
    @Operation(summary = "Les cartes des créneaux où j'étais",
        description = "Présence confirmée, pas simple inscription.")
    public List<SlotRecapDto> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return recapService.getMine(principal.getId());
    }
}
