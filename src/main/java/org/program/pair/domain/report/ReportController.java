package org.program.pair.domain.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.report.dto.CreateReportRequest;
import org.program.pair.domain.report.dto.ReportDto;
import org.program.pair.domain.report.dto.ReportModerationDto;
import org.program.pair.domain.report.dto.ReportSummaryDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Système de signalements et modération")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    // Aucune méthode ne rend l'entité Report, ni directement ni dans une Page
    // (P-BA-12) : ArchitectureTest le vérifie pour tous les contrôleurs.

    private final ReportService reportService;

    /**
     * Le {@code 201} est porté par {@code @ResponseStatus} et non par un
     * {@code ResponseEntity.status(...)} : springdoc ne lit que la signature de
     * la méthode, jamais le corps. Un statut posé à l'exécution laissait
     * {@code /v3/api-docs} annoncer le {@code 200} par défaut — le serveur et
     * son contrat se contredisaient sans que rien ne le signale. C'est la même
     * forme que {@code POST /api/programs/{programId}/report}, qui se documentait
     * juste pour cette raison.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Signaler un contenu", description = "Signaler un utilisateur, programme, message ou avis")
    public ReportDto createReport(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateReportRequest request) {

        return ReportDto.from(reportService.createReport(currentUser.getId(), request));
    }

    /**
     * Le suivi de ses propres signalements.
     *
     * <p><b>Rend {@link ReportSummaryDto}, et non l'entité.</b> Cette route
     * servait {@code Page<Report>} : l'entité est annotée {@code @Data}, donc
     * tous ses champs partaient — {@code resolutionNotes}, les notes internes du
     * modérateur, et {@code reviewedBy}, son identifiant. La forme fermée est ce
     * qui referme cette fuite ; elle n'est pas une préférence de style.
     *
     * <p>Le type de retour est déclaré dans la signature parce que springdoc ne
     * lit que celle-ci : le contrat annoncerait {@code Report} si la projection
     * n'avait lieu que dans le corps, et le client coderait contre une forme que
     * le serveur ne sert plus. C'est la même raison qui a fait porter le
     * {@code 201} de la création par {@code @ResponseStatus}.
     */
    @GetMapping("/me")
    @Operation(summary = "Mes signalements", description = "Signalements que j'ai créés, et où ils en sont")
    public ResponseEntity<Page<ReportSummaryDto>> getMyReports(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(reportService.getMyReports(currentUser.getId(), PageRequest.of(page, size)));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Signalements en attente (Modérateurs)", description = "Liste des signalements à traiter")
    public ResponseEntity<Page<ReportModerationDto>> getPendingReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(reportService.getPendingReports(PageRequest.of(page, size))
            .map(ReportModerationDto::from));
    }

    @PutMapping("/{reportId}/review")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Traiter un signalement (Modérateurs)", description = "Changer le statut d'un signalement")
    public ResponseEntity<ReportModerationDto> reviewReport(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam ReportStatus status,
            @RequestParam(required = false) String notes) {

        return ResponseEntity.ok(ReportModerationDto.from(
            reportService.reviewReport(reportId, currentUser.getId(), status, notes)));
    }
}
