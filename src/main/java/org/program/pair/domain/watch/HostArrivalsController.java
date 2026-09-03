package org.program.pair.domain.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.watch.dto.PendingArrivalDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /**
     * L'organisateur valide l'arrivée d'un inscrit qui l'a déclarée.
     *
     * <p>Visé par {@code participationId} : c'est la ligne de la liste des inscrits
     * qu'il touche, et il n'a aucune raison de manipuler l'identifiant d'une veille.
     *
     * <p><b>{@code 202} dans tous les cas où le créneau est le sien</b>, y compris
     * sur un inscrit qui n'a rien armé ou rien déclaré. C'est la contrainte de
     * confidentialité du client, et elle ne tient qu'à ce prix : un 404 ou un 409
     * ferait de ce verbe un détecteur, et l'organisateur apprendrait qui se protège
     * en essayant. Ce qui doit être indistinguable n'est pas seulement la donnée,
     * c'est le geste disponible.
     */
    @PostMapping("/api/schedules/{scheduleId}/arrivals/{participationId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Valider la présence d'un inscrit (organisateur)",
        description = "Pose `arrivalConfirmedAt`, passe la veille ON_SITE et fait courir "
            + "l'échéance de retour. Le code de retour, lui, n'est pas tiré ici : la personne "
            + "le demande elle-même par `POST /watches/{id}/code/claim`.\n\n"
            + "404 — jamais 403 — quand le créneau n'est pas le sien. 202 sans effet quand il "
            + "n'y a rien à valider : c'est ce qui empêche ce verbe de dire qui a armé une "
            + "veille.\n\n"
            + "Sans geste de l'organisateur, la validation tombe d'elle-même au bout de 15 min "
            + "— voir `arrivalAutoConfirmAt`. Il gagne du temps sur la validation ; il n'a "
            + "jamais de pouvoir sur elle.")
    public void confirmArrival(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @PathVariable UUID participationId) {
        watchService.confirmArrival(principal.getId(), scheduleId, participationId);
    }
}
