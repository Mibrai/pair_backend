package org.program.pair.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.gdpr.GdprService;
import org.program.pair.domain.gdpr.dto.GdprExportDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * GDPR Compliance API
 * Implements EU GDPR requirements
 */
@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
@Tag(name = "GDPR", description = "GDPR compliance endpoints")
@SecurityRequirement(name = "bearer-jwt")
public class GdprController {

    private final GdprService gdprService;
    private final UserService userService;
    private final AuditLogService auditLogService;

    /**
     * Export all user data (GDPR Article 15: Right of access)
     */
    @GetMapping("/export")
    @Operation(
            summary = "Export my personal data",
            description = "Export all personal data in machine-readable format (GDPR Article 15). " +
                    "Returns JSON containing all user data: profile, activities, programs, messages, reviews, etc."
    )
    public ResponseEntity<GdprExportDto> exportMyData(@AuthenticationPrincipal UserPrincipal principal) {
        GdprExportDto export = gdprService.exportUserData(principal.getId());
        return ResponseEntity.ok(export);
    }

    /**
     * Supprimer son compte (RGPD article 17 : droit à l'effacement).
     *
     * <p><b>Cette route ne faisait rien.</b> Son corps tenait en trois
     * commentaires décrivant ce qu'elle aurait dû faire — « This triggers user
     * deactivation », « Implementation in UserService.deactivateAccount() » —
     * puis un {@code 204}. C'est la route que l'application appelle, la seule :
     * aucun écran ne passe par {@code DELETE /users/me}, où la désactivation
     * était pourtant branchée. Chaque personne qui a touché le bouton
     * « supprimer mon compte » a donc vu l'app confirmer, et son compte est resté
     * ouvert. Aucune trace n'en subsiste en base, la route n'écrivant rien : les
     * demandes antérieures se relèvent dans les journaux HTTP, pas ici.
     *
     * <p><b>Le {@code 204} est inchangé</b>, corps vide compris : l'application
     * en circulation devient conforme à sa propre documentation sans rien
     * changer chez elle.
     *
     * <p>La trace d'audit n'est pas un journal d'exploitation, c'est
     * <b>l'horodatage de la demande</b> : tant que {@code users.deactivated_at}
     * n'existe pas, elle est la seule chose qui dit quand le délai de trente
     * jours a commencé. Elle est écrite exactement de la même façon par
     * {@code DELETE /users/me}, pour que les deux routes soient indiscernables —
     * ni en état, ni en trace.
     */
    @DeleteMapping("/delete-account")
    @Operation(
            summary = "Request account deletion",
            description = "Request permanent account deletion (GDPR Article 17). " +
                    "Account is deactivated immediately. " +
                    "After 30 days, all personal data is permanently anonymized."
    )
    public ResponseEntity<Void> requestAccountDeletion(@AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = principal.getId();
        userService.deactivateAccount(userId);
        auditLogService.log(userId, AuditActionType.GDPR_DELETE_REQUEST, "USER", userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
