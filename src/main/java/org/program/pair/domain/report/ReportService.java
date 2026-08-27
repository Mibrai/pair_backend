package org.program.pair.domain.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.report.dto.CreateReportRequest;
import org.program.pair.repository.MessageRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ReportRepository;
import org.program.pair.repository.ReviewRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final UserRepository userRepository;
    private final ProgramRepository programRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;

    public Report createReport(UUID reporterId, CreateReportRequest request) {
        // Se signaler soi-même créait un vrai signalement, en PENDING, qui
        // occupait la file de modération sans rien vouloir dire. Ce n'est ni un
        // risque de sécurité ni une panne — c'est du bruit, et c'est pour cela
        // que la règle a attendu une décision plutôt qu'un correctif.
        //
        // 422 et non 409 : ce n'est pas « c'est déjà fait », c'est « vous n'avez
        // pas à faire ça » — le partage exact que le lot du 26/08 avait tranché
        // entre les deux codes. Même forme que le refus de se recommander
        // soi-même dans PeerRecommendationService.
        //
        // Restreint à USER, le seul cas signalé : signaler son propre programme
        // ou son propre message est tout aussi vide de sens, mais ce serait une
        // règle que personne n'a demandée, posée sur des cas que personne n'a
        // observés.
        if (request.getReportedEntityType() == ReportEntityType.USER
                && reporterId.equals(request.getReportedEntityId())) {
            throw new BusinessException("Vous ne pouvez pas vous signaler vous-même");
        }

        // La cible d'abord : signaler du vide n'est pas un incident, c'est une
        // requête qui ne désigne rien, et l'app en tire « cette personne n'est
        // plus joignable » plutôt qu'une panne. Avant, aucun de ces quatre types
        // n'était résolu et un identifiant inexistant allait jusqu'à l'insertion.
        if (!cibleExiste(request.getReportedEntityType(), request.getReportedEntityId())) {
            throw new ResourceNotFoundException(
                "L'élément signalé n'existe pas ou n'est plus disponible.");
        }

        // Check if already reported
        var existing = reportRepository.findByReporterIdAndReportedEntityTypeAndReportedEntityId(
            reporterId,
            request.getReportedEntityType(),
            request.getReportedEntityId()
        );

        if (existing.isPresent()) {
            throw dejaSignale();
        }

        // Pas d'id assigné ici : @GeneratedValue le pose. Un id posé à la main rend
        // save() non-« new » pour Spring Data, qui appelle alors merge() au lieu de
        // persist() ; Hibernate 7 refuse de fusionner une instance détachée dont la
        // ligne n'existe pas et lève StaleObjectStateException — c'était le 500 du
        // chemin nominal de cette écriture.
        Report report = Report.builder()
            .reporterId(reporterId)
            .reportedEntityType(request.getReportedEntityType())
            .reportedEntityId(request.getReportedEntityId())
            .reason(request.getReason())
            .description(request.getDescription())
            .status(ReportStatus.PENDING)
            .build();

        try {
            // saveAndFlush et non save : l'identifiant étant généré en mémoire,
            // l'INSERT ne partirait qu'au commit, donc hors de ce try — et la
            // violation de contrainte ressortirait en 500.
            report = reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            // Deux signalements simultanés du même élément par la même personne :
            // le SELECT ci-dessus les laisse passer tous les deux, seule la
            // contrainte unique_report tranche. Le second est un « déjà signalé »
            // comme un autre, pas un 500.
            throw dejaSignale();
        }
        log.info("User {} reported {} {} for {}",
            reporterId, request.getReportedEntityType(), request.getReportedEntityId(), request.getReason());

        return report;
    }

    private boolean cibleExiste(ReportEntityType type, UUID entityId) {
        return switch (type) {
            case USER -> userRepository.existsById(entityId);
            case PROGRAM -> programRepository.existsById(entityId);
            case MESSAGE -> messageRepository.existsById(entityId);
            case REVIEW -> reviewRepository.existsById(entityId);
        };
    }

    /**
     * {@code 409} et non {@code 422} : « c'est déjà fait » est un état, pas un
     * refus de droit. L'app en tire un rappel neutre — le signalement précédent
     * tient toujours — là où le {@code 422} lui faisait annoncer un refus.
     */
    private ConflictException dejaSignale() {
        return new ConflictException(
            ErrorCode.REPORT_ALREADY_SUBMITTED, "Vous avez déjà signalé cet élément");
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
