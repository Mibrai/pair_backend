package org.program.pair.domain.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.report.dto.CreateReportRequest;
import org.program.pair.repository.ReportRepository;
import org.program.pair.shared.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final ReportRepository reportRepository;

    public Report createReport(UUID reporterId, CreateReportRequest request) {
        // Check if already reported
        var existing = reportRepository.findByReporterIdAndReportedEntityTypeAndReportedEntityId(
            reporterId,
            request.getReportedEntityType(),
            request.getReportedEntityId()
        );

        if (existing.isPresent()) {
            throw new BusinessException("Vous avez déjà signalé cet élément");
        }

        Report report = Report.builder()
            .id(UUID.randomUUID())
            .reporterId(reporterId)
            .reportedEntityType(request.getReportedEntityType())
            .reportedEntityId(request.getReportedEntityId())
            .reason(request.getReason())
            .description(request.getDescription())
            .status(ReportStatus.PENDING)
            .build();

        report = reportRepository.save(report);
        log.info("User {} reported {} {} for {}",
            reporterId, request.getReportedEntityType(), request.getReportedEntityId(), request.getReason());

        return report;
    }

    @Transactional(readOnly = true)
    public Page<Report> getPendingReports(Pageable pageable) {
        return reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Report> getMyReports(UUID userId, Pageable pageable) {
        return reportRepository.findByReporterIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Report reviewReport(UUID reportId, UUID moderatorId, ReportStatus newStatus, String notes) {
        Report report = reportRepository.findById(reportId)
            .orElseThrow(() -> new BusinessException("Signalement non trouvé"));

        report.setStatus(newStatus);
        report.setReviewedBy(moderatorId);
        report.setReviewedAt(Instant.now());
        report.setResolutionNotes(notes);

        return reportRepository.save(report);
    }
}
