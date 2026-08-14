package org.program.pair.domain.recap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.recap.dto.RecapRequests;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Contribution à la carte-souvenir d'un créneau, et lecture de celle-ci.
 *
 * <p>Sous {@code /api/slots/{scheduleId}/recap}, à côté du créneau dont elle est
 * la trace. Toutes les routes de contribution créent la carte si elle n'existe
 * pas encore : elle naît de la première contribution.
 */
@RestController
@RequestMapping("/api/slots/{scheduleId}/recap")
@RequiredArgsConstructor
public class SlotRecapController {

    private final SlotRecapService recapService;

    @GetMapping
    @Operation(summary = "Lire la carte-souvenir d'un créneau",
        description = "Rend 404 — et jamais 403 — quand la carte existe mais n'est pas "
            + "lisible par l'appelant : ne pas révéler l'existence d'une ressource "
            + "inaccessible.")
    @ApiResponse(responseCode = "404", description = "Aucune carte, ou carte non lisible par l'appelant")
    public SlotRecapDto get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return recapService.get(scheduleId, principal.getId());
    }

    @PostMapping("/vibes")
    @Operation(summary = "Poser ses ambiances",
        description = "Remplace la contribution de l'appelant plutôt que de s'y ajouter : "
            + "le client envoie l'ensemble de sa sélection à chaque tap. Deux valeurs au "
            + "maximum ; un tableau vide équivaut au DELETE.")
    @ApiResponse(responseCode = "403", description = "L'appelant n'était pas présent (RECAP_NOT_ATTENDEE)")
    @ApiResponse(responseCode = "409", description = "Fenêtre de sept jours refermée (RECAP_WINDOW_CLOSED)")
    @ApiResponse(responseCode = "422", description = "Plus de deux ambiances, ou valeur inconnue (RECAP_INVALID_VIBES)")
    public SlotRecapDto vote(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RecapRequests.VibesRequest request) {
        return recapService.voteVibes(principal.getId(), scheduleId,
            request != null ? request.vibes() : List.of());
    }

    @DeleteMapping("/vibes")
    public SlotRecapDto clearVibes(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return recapService.clearVibes(principal.getId(), scheduleId);
    }

    @PatchMapping("/consent")
    @Operation(summary = "Accepter — ou retirer — d'apparaître nommé",
        description = "Faux par défaut. Retirable à tout moment, y compris après "
            + "publication : la carte se régénère alors sans l'appelant.")
    public SlotRecapDto consent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RecapRequests.ConsentRequest request) {
        return recapService.setConsent(principal.getId(), scheduleId,
            Boolean.TRUE.equals(request.showIdentity()));
    }

    @PatchMapping("/photo")
    @Operation(summary = "Rattacher son souvenir photo",
        description = "N'est pas un chemin d'upload : le fichier est passé par "
            + "POST /api/media/upload/image, et cette route ne fait que le rattacher à une "
            + "présence confirmée. photoUrl nulle retire le souvenir.")
    public SlotRecapDto photo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RecapRequests.MemoryPhotoRequest request) {
        return recapService.setMemoryPhoto(principal.getId(), scheduleId,
            request.photoUrl(), Boolean.TRUE.equals(request.isPublic()));
    }

    @PatchMapping("/note")
    @Operation(summary = "Le mot de l'hôte (hôte uniquement)")
    @ApiResponse(responseCode = "403", description = "L'appelant n'est pas l'hôte (RECAP_NOT_HOST)")
    public SlotRecapDto note(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RecapRequests.HostNoteRequest request) {
        return recapService.setHostNote(principal.getId(), scheduleId, request.note());
    }

    @PatchMapping("/visibility")
    @Operation(summary = "Publier ou dépublier la carte (hôte uniquement)",
        description = "PARTICIPANTS est accepté et traité comme PRIVATE.")
    @ApiResponse(responseCode = "403", description = "L'appelant n'est pas l'hôte (RECAP_NOT_HOST)")
    @ApiResponse(responseCode = "409",
        description = "Aucun participant non-hôte n'a confirmé sa présence (RECAP_NEEDS_ATTENDEE)")
    public SlotRecapDto visibility(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RecapRequests.VisibilityRequest request) {
        return recapService.setVisibility(principal.getId(), scheduleId, request.visibility());
    }
}
