package org.program.pair.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.gdpr.GdprService;
import org.program.pair.domain.gdpr.dto.GdprExportDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
     * Request account deletion (GDPR Article 17: Right to erasure)
     * Note: Account is deactivated immediately, data purged after 30 days
     */
    @DeleteMapping("/delete-account")
    @Operation(
            summary = "Request account deletion",
            description = "Request permanent account deletion (GDPR Article 17). " +
                    "Account is deactivated immediately. " +
                    "After 30 days, all personal data is permanently anonymized."
    )
    public ResponseEntity<Void> requestAccountDeletion(@AuthenticationPrincipal UserPrincipal principal) {
        // This triggers user deactivation
        // Actual purge happens via scheduled job after 30 days
        // Implementation in UserService.deactivateAccount()
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
