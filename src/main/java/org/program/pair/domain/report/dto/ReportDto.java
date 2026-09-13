package org.program.pair.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.report.Report;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportReason;
import org.program.pair.domain.report.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Le signalement que son auteur vient de créer (P-BA-12).
 *
 * <p>La réponse de {@code POST /api/reports} était l'entité {@code Report}
 * elle-même : tout champ ajouté à la table partait au client sans que personne
 * l'ait décidé. Cette forme est fermée, et ne porte que ce que l'auteur a écrit
 * et le statut que le serveur a posé — jamais {@code resolutionNotes} ni
 * {@code reviewedBy}, qui appartiennent à la modération.
 */
@Schema(description = "Un signalement tel que son auteur vient de le créer.")
public record ReportDto(
    UUID id,
    ReportEntityType reportedEntityType,
    UUID reportedEntityId,
    ReportReason reason,

    @Schema(description = "Statut de traitement. PENDING à la création.")
    ReportStatus status,

    Instant createdAt
) {

    public static ReportDto from(Report report) {
        return new ReportDto(
            report.getId(),
            report.getReportedEntityType(),
            report.getReportedEntityId(),
            report.getReason(),
            report.getStatus(),
            report.getCreatedAt());
    }
}
