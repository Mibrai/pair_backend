package org.program.pair.domain.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.watch.dto.CreateWatchRequest;
import org.program.pair.domain.watch.dto.WatchDetailDto;
import org.program.pair.domain.watch.dto.WatchDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Les veilles retour de l'appelant : armer, lister les actives, lire l'une avec
 * sa chronologie, désarmer avant départ.
 *
 * <p>Tout est authentifié et sous {@code /api} : une veille est celle d'une
 * personne. Les gestes qui suivent l'armement — arrivée, clôture, minuteurs —
 * arrivent avec les priorités suivantes du lot.
 */
@RestController
@RequestMapping("/api/watches")
@RequiredArgsConstructor
@Tag(name = "Watches", description = "Veilles retour")
@SecurityRequirement(name = "bearerAuth")
public class WatchController {

    private final WatchService watchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Armer une veille",
        description = "Exige un contact d'urgence accepté. L'échéance vaut par défaut "
            + "la fin du créneau plus une heure.")
    public WatchDto arm(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateWatchRequest request) {
        return watchService.arm(principal.getId(), request);
    }

    @GetMapping("/active")
    @Operation(summary = "Mes veilles actives")
    public List<WatchDto> active(@AuthenticationPrincipal UserPrincipal principal) {
        return watchService.listActive(principal.getId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Une veille et sa chronologie")
    public WatchDetailDto detail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.detail(principal.getId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Désarmer avant départ",
        description = "Éteint une veille encore armée, sans message et sans compter d'absence.")
    public void disarm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        watchService.disarm(principal.getId(), id);
    }
}
