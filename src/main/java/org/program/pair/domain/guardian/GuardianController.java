package org.program.pair.domain.guardian;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.guardian.dto.CreateGuardianRequest;
import org.program.pair.domain.guardian.dto.GuardianDto;
import org.program.pair.domain.guardian.dto.SetGuardianRoleRequest;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Les contacts d'urgence de l'appelant.
 *
 * <p>Tout est sous {@code /api} et authentifié : ce sont les contacts d'une
 * personne, personne d'autre n'a à les lire. Le flux de consentement, lui, est
 * public et vit ailleurs ({@code PublicGuardianConsentController}) — c'est le
 * contact, sans compte, qui l'emprunte.
 */
@RestController
@RequestMapping("/api/guardians")
@RequiredArgsConstructor
@Tag(name = "Guardians", description = "Contacts d'urgence de la veille retour")
@SecurityRequirement(name = "bearerAuth")
public class GuardianController {

    private final GuardianService guardianService;

    @GetMapping
    @Operation(summary = "Mes contacts d'urgence")
    public List<GuardianDto> list(@AuthenticationPrincipal UserPrincipal principal) {
        return guardianService.list(principal.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Désigner un contact d'urgence",
        description = "Un membre meetDo, ou un contact externe (nom + téléphone et/ou e-mail).")
    public GuardianDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateGuardianRequest request) {
        return guardianService.create(principal.getId(), request);
    }

    @PostMapping("/{id}/invite")
    @Operation(summary = "Envoyer la demande d'accord",
        description = "Envoie le message ①, une seule fois. Refuse la relance.")
    public GuardianDto invite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return guardianService.invite(principal.getId(), id);
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Poser le rôle d'un contact",
        description = "PRIMARY, BACKUP ou NONE. **Poser un rôle le retire au contact qui le "
            + "portait**, dans la même transaction : au plus un principal et un secours par "
            + "personne, tenu par la base et non par le client — deux appareils du même compte "
            + "peuvent poser deux principaux sans jamais se croiser. Un contact PENDING accepte "
            + "un rôle (on désigne, puis on invite) ; un contact REFUSED le refuse, et perd "
            + "celui qu'il avait au moment du refus. Le contact n'apprend jamais son rôle.")
    public GuardianDto setRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SetGuardianRoleRequest request) {
        return guardianService.setRole(principal.getId(), id, request.role());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un contact d'urgence")
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        guardianService.delete(principal.getId(), id);
    }
}
