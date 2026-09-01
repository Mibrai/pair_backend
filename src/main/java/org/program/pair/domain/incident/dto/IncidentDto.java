package org.program.pair.domain.incident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.incident.Incident;
import org.program.pair.domain.incident.IncidentTarget;

import java.time.Instant;
import java.util.UUID;

/**
 * Un incident tel que la personne qui l'a vécu ou signalé le voit.
 */
@Schema(description = "Un incident de sécurité.")
public record IncidentDto(
    UUID id,
    IncidentTarget target,
    UUID scheduleId,
    String note,
    String attachmentUrl,
    Instant createdAt
) {
    public static IncidentDto from(Incident i) {
        return new IncidentDto(
            i.getId(), i.getTarget(), i.getScheduleId(),
            i.getNote(), i.getAttachmentUrl(), i.getCreatedAt());
    }
}
