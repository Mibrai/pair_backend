package org.program.pair.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportReason;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReportRequest {

    @NotNull(message = "Le type d'entité est requis")
    private ReportEntityType reportedEntityType;

    @NotNull(message = "L'ID de l'entité est requis")
    private UUID reportedEntityId;

    @NotNull(message = "La raison est requise")
    private ReportReason reason;

    @NotBlank(message = "La description est requise")
    @Size(min = 10, max = 500, message = "La description doit contenir entre 10 et 500 caractères")
    private String description;
}
