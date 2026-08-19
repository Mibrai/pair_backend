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
    private final org.program.pair.domain.publicslot.PublicSlotService publicSlotService;

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

    @org.springframework.web.bind.annotation.GetMapping("/{scheduleId}/share-link")
    @Operation(summary = "L'adresse publique de ce créneau.",
        description = "Créée à la première demande — un créneau que personne n'a jamais "
            + "partagé n'a pas besoin d'adresse publique. Réservée aux personnes du "
            + "créneau : l'adresse est publique, mais la fabriquer ne l'est pas.")
    public org.program.pair.domain.publicslot.dto.PublicShareLinkDto shareLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return publicSlotService.shareLink(principal.getId(), scheduleId);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{scheduleId}/shareable")
    @Operation(summary = "Ouvre ou ferme le partage public de ce créneau.",
        description = "Réservé à l'organisateur, là où /share-link s'ouvre à tous les "
            + "participants : refermer retire à tous les autres un lien qu'ils ont "
            + "peut-être déjà collé quelque part. Le jeton n'est jamais effacé ni "
            + "régénéré — rouvrir rend valides les liens déjà partagés, un jeton neuf "
            + "transformerait une pause en rupture définitive. 404 pour qui n'organise "
            + "pas, jamais 403.")
    public org.program.pair.domain.publicslot.dto.PublicShareLinkDto setShareable(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                org.program.pair.domain.publicslot.dto.SetShareableRequest request) {
        return publicSlotService.setShareable(
            principal.getId(), scheduleId, request.isPubliclyShareable());
    }
}
