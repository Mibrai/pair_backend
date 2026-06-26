package org.program.pair.repository;

import org.program.pair.domain.report.Report;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    Optional<Report> findByReporterIdAndReportedEntityTypeAndReportedEntityId(
        UUID reporterId,
        ReportEntityType entityType,
        UUID entityId
    );

    Page<Report> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    Page<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId, Pageable pageable);

    List<Report> findByReportedEntityTypeAndReportedEntityId(ReportEntityType entityType, UUID entityId);

    long countByReportedEntityTypeAndReportedEntityId(ReportEntityType entityType, UUID entityId);

    long countByStatus(ReportStatus status);
}
