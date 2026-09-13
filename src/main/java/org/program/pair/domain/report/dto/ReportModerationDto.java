package org.program.pair.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.report.Report;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportReason;
import org.program.pair.domain.report.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Un signalement vu par la modération (P-BA-12) : la seule forme qui porte les
 * notes et l'identité du modérateur. Servie uniquement par les routes
 * {@code /pending} et {@code /review}, réservées aux rôles de modération.
 */
@Schema(description = "Un signalement vu par la modération, notes internes comprises.")
public record ReportModerationDto(
    UUID id,
    UUID reporterId,
    ReportEntityType reportedEntityType,
    UUID reportedEntityId,
    ReportReason reason,
    String description,
    ReportStatus status,

    @Schema(description = "Modérateur qui a tranché, ou null.")
    UUID reviewedBy,

    Instant reviewedAt,

    @Schema(description = "Notes internes de modération. Jamais servies à l'auteur du signalement.")
    String resolutionNotes,

    Instant createdAt,
    Instant updatedAt
) {

    public static ReportModerationDto from(Report report) {
        return new ReportModerationDto(
            report.getId(),
            report.getReporterId(),
            report.getReportedEntityType(),
            report.getReportedEntityId(),
            report.getReason(),
            report.getDescription(),
            report.getStatus(),
            report.getReviewedBy(),
            report.getReviewedAt(),
            report.getResolutionNotes(),
            report.getCreatedAt(),
            report.getUpdatedAt());
    }
}
