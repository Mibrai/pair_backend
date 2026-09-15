package org.program.pair.domain.review;

import org.program.pair.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.review.dto.CreateReviewRequest;
import org.program.pair.domain.review.dto.ReviewDto;
import org.program.pair.domain.review.dto.ReviewSummaryDto;
import org.program.pair.domain.trust.InteractionProofType;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ReviewRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ConversationRepository conversationRepository;
    private final AttendanceRepository attendanceRepository;
    private final ProgramRepository programRepository;
    private final BlockFilterService blockFilterService;

    public Review createReview(UUID reviewerId, CreateReviewRequest request) {
        UUID programId = request.getProgramId();

        var program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_PROGRAMME_INTROUVABLE", "Programme non trouvé"));

        UUID creatorId = program.getUserActivity() != null && program.getUserActivity().getUser() != null
            ? program.getUserActivity().getUser().getId()
            : null;

        if (creatorId == null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "REFUS_PROGRAMME_SANS_CREATEUR", "Programme sans créateur identifié");
        }

        if (reviewerId.equals(creatorId)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "REFUS_AVIS_PROPRE_PROGRAMME", "Vous ne pouvez pas évaluer votre propre programme");
        }

        if (reviewRepository.findByReviewerIdAndProgramId(reviewerId, programId).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "REFUS_AVIS_DEJA_DONNE", "Vous avez déjà évalué ce programme");
        }

        // Preuve d'interaction requise : conversation directe avec l'organisateur,
        // ou présence partagée confirmée sur un même créneau (SHARED_ATTENDANCE).
        UUID conversationId = conversationRepository.findDirectBetween(reviewerId, creatorId)
            .map(c -> c.getId())
            .orElse(null);

        InteractionProofType proofType;
        if (conversationId != null) {
            proofType = InteractionProofType.CONVERSATION;
        } else if (attendanceRepository.existsSharedPresence(reviewerId, creatorId)) {
            proofType = InteractionProofType.SHARED_ATTENDANCE;
        } else {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "REFUS_AVIS_SANS_INTERACTION",
                "Vous devez avoir échangé des messages ou partagé une présence confirmée avec l'organisateur avant de pouvoir évaluer ce programme");
        }

        // Pas d'id assigné ici : @GeneratedValue le pose. Un id posé à la main rend
        // save() non-« new » pour Spring Data, qui appelle alors merge() au lieu de
        // persist() ; Hibernate 7 refuse de fusionner une instance détachée dont la
        // ligne n'existe pas et lève StaleObjectStateException — c'était le 500 du
        // chemin nominal de cette écriture.
        Review review = Review.builder()
            .reviewerId(reviewerId)
            .programId(programId)
            .interactionProofId(conversationId)
            .interactionProofType(proofType)
            // Aucune note enregistrée (P-BL-10, D4) : celle que l'app envoie
            // encore est ignorée.
            .score(null)
            .comment(request.getComment())
            .recommend(Boolean.TRUE.equals(request.getRecommend()))
            .build();

        review = reviewRepository.save(review);
        log.info("User {} reviewed program {}", reviewerId, programId);

        return review;
    }

    /**
     * Les avis d'un programme, <b>pour qui a le droit de les lire</b> (P-BL-10, D4).
     *
     * <p>Le commentaire est devenu un retour privé : l'organisateur du programme
     * lit tous les avis, l'auteur d'un avis lit le sien, et personne d'autre ne
     * lit rien. Un autre lecteur reçoit une page vide en {@code 200}, pas un
     * {@code 403} : les versions 1.1.0+16 et +17 de l'app affichent cet onglet à
     * tout visiteur, et y liraient un 403 comme « impossible de charger les
     * avis » (réponse de l'app du 14/09).
     */
    @Transactional(readOnly = true)
    public Page<Review> getProgramReviews(UUID appelantId, UUID programId, Pageable pageable) {
        introuvableSiAuteurBloque(appelantId, programId);
        if (estOrganisateur(appelantId, programId)) {
            return reviewRepository.findByProgramIdOrderByCreatedAtDesc(programId, pageable);
        }
        return reviewRepository.findByProgramIdAndReviewerIdOrderByCreatedAtDesc(
            programId, appelantId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Review> getUserReviews(UUID userId, Pageable pageable) {
        return reviewRepository.findByReviewerIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public boolean canReview(UUID reviewerId, UUID programId) {
        var program = programRepository.findById(programId).orElse(null);
        if (program == null) return false;

        UUID creatorId = program.getUserActivity() != null && program.getUserActivity().getUser() != null
            ? program.getUserActivity().getUser().getId()
            : null;

        if (creatorId == null) return false;
        if (reviewerId.equals(creatorId)) return false;
        if (reviewRepository.findByReviewerIdAndProgramId(reviewerId, programId).isPresent()) return false;

        return conversationRepository.findDirectBetween(reviewerId, creatorId).isPresent()
            || attendanceRepository.existsSharedPresence(reviewerId, creatorId);
    }

    @Transactional(readOnly = true)
    public ReviewSummaryDto getProgramReviewSummary(UUID appelantId, UUID programId) {
        introuvableSiAuteurBloque(appelantId, programId);
        // Même lecture que la liste : le décompte et les avis récents ne portent
        // que sur ce que l'appelant a le droit de lire. Un total public dirait
        // combien d'avis existent sur un programme dont on ne lit plus aucun.
        boolean organisateur = estOrganisateur(appelantId, programId);
        long total = organisateur
            ? reviewRepository.countByProgramId(programId)
            : reviewRepository.countByProgramIdAndReviewerId(programId, appelantId);

        List<ReviewDto> recent = getProgramReviews(appelantId, programId, PageRequest.of(0, 5))
            .stream()
            .map(ReviewDto::fromEntity)
            .toList();

        // Plus de moyenne publique (P-BL-10) : null, le champ reste déclaré.
        return new ReviewSummaryDto(programId, null, total, recent);
    }

    private boolean estOrganisateur(UUID appelantId, UUID programId) {
        return programRepository.findById(programId)
            .filter(p -> p.getUserActivity() != null && p.getUserActivity().getUser() != null)
            .map(p -> p.getUserActivity().getUser().getId().equals(appelantId))
            .orElse(false);
    }

    /**
     * Les avis d'un programme dont l'auteur et l'appelant sont bloqués, dans un
     * sens ou dans l'autre, sont introuvables (P-BS-14 étape 3, décision du
     * 13/09) : le même 404 qu'un programme inexistant, comme le profil et les
     * recommandations. Un programme inconnu garde sa page vide.
     */
    private void introuvableSiAuteurBloque(UUID appelantId, UUID programId) {
        programRepository.findById(programId)
            .filter(p -> p.getUserActivity() != null && p.getUserActivity().getUser() != null)
            .map(p -> p.getUserActivity().getUser().getId())
            .filter(auteurId -> blockFilterService.blocked(appelantId, auteurId))
            .ifPresent(auteurId -> {
                throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_PROGRAMME_INTROUVABLE", "Programme introuvable.");
            });
    }
}
