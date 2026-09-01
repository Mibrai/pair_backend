package org.program.pair.domain.guardian;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.guardian.dto.CreateGuardianRequest;
import org.program.pair.domain.guardian.dto.GuardianDto;
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un contact d'urgence")
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        guardianService.delete(principal.getId(), id);
    }
}
