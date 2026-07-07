package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.program.pair.domain.report.ReportReason;

public record ProgramReportRequest(
    @NotNull ReportReason reason,
    @Size(min = 10, max = 500) String description
) {}
