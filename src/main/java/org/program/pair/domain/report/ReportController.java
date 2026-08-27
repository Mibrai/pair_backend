package org.program.pair.domain.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.report.dto.CreateReportRequest;
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
    public Report createReport(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateReportRequest request) {

        return reportService.createReport(currentUser.getId(), request);
    }

    @GetMapping("/me")
    @Operation(summary = "Mes signalements", description = "Signalements que j'ai créés")
    public ResponseEntity<Page<Report>> getMyReports(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Report> reports = reportService.getMyReports(currentUser.getId(), PageRequest.of(page, size));
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Signalements en attente (Modérateurs)", description = "Liste des signalements à traiter")
    public ResponseEntity<Page<Report>> getPendingReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<Report> reports = reportService.getPendingReports(PageRequest.of(page, size));
        return ResponseEntity.ok(reports);
    }

    @PutMapping("/{reportId}/review")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Traiter un signalement (Modérateurs)", description = "Changer le statut d'un signalement")
    public ResponseEntity<Report> reviewReport(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam ReportStatus status,
            @RequestParam(required = false) String notes) {

        Report report = reportService.reviewReport(reportId, currentUser.getId(), status, notes);
        return ResponseEntity.ok(report);
    }
}
