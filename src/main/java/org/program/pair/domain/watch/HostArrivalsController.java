package org.program.pair.domain.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.watch.dto.PendingArrivalDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un organisateur voit des arrivées de ses inscrits.
 *
 * <p>Sous {@code /api/schedules} et non {@code /api/watches} : la liste est
 * attachée à un créneau, vue par son organisateur, et non à une veille vue par sa
 * propriétaire. Le geste correspondant — « je la vois » — vit, lui, sur la veille
 * ({@code POST /api/watches/{id}/seen-by-host}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Watches", description = "Arrivées attendues, côté organisateur")
@SecurityRequirement(name = "bearerAuth")
public class HostArrivalsController {

    private final WatchService watchService;

    @GetMapping("/api/schedules/{scheduleId}/pending-arrivals")
    @Operation(summary = "Les inscrits que j'attends encore (organisateur)",
        description = "Un nom, une absence, une heure — rien d'autre. Réservé à l'organisateur (404 sinon).")
    public List<PendingArrivalDto> pendingArrivals(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return watchService.pendingArrivals(principal.getId(), scheduleId);
    }
}
