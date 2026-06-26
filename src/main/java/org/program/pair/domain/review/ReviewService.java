package org.program.pair.domain.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.review.dto.CreateReviewRequest;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ReviewRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ConversationRepository conversationRepository;
    private final ProgramRepository programRepository;

    private static final Set<String> VALID_CRITERIA = Set.of(
        "ORGANIZATION", "COMMUNICATION", "ATMOSPHERE", "DIFFICULTY", "RECOMMENDATION"
    );

    /**
     * Crée un avis sur un programme
     */
    public Review createReview(UUID reviewerId, CreateReviewRequest request) {
        UUID programId = request.getProgramId();

        // Validate program exists
        var program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme non trouvé"));

        UUID creatorId = program.getUserActivity() != null && program.getUserActivity().getUser() != null
            ? program.getUserActivity().getUser().getId()
            : null;

        if (creatorId == null) {
            throw new BusinessException("Programme sans créateur identifié");
        }

        // Validation 1: Cannot review own program
        if (reviewerId.equals(creatorId)) {
            throw new BusinessException("Vous ne pouvez pas évaluer votre propre programme");
        }

        // Validation 2: Check if already reviewed
        if (reviewRepository.findByReviewerIdAndProgramId(reviewerId, programId).isPresent()) {
            throw new BusinessException("Vous avez déjà évalué ce programme");
        }

        // Validation 3: Must have conversation with program creator
        UUID conversationId = conversationRepository.findDirectBetween(reviewerId, creatorId)
            .map(c -> c.getId())
            .orElseThrow(() -> new BusinessException(
                "Vous devez avoir échangé des messages avec le créateur du programme avant de pouvoir l'évaluer"
            ));

        // Validation 4: Validate criteria scores
        validateCriteriaScores(request.getCriteriaScores());

        // Create review
        Review review = Review.builder()
            .id(UUID.randomUUID())
            .reviewerId(reviewerId)
            .programId(programId)
            .conversationId(conversationId)
            .overallRating(request.getOverallRating())
            .criteriaScores(request.getCriteriaScores())
            .comment(request.getComment())
            .build();

        review = reviewRepository.save(review);
        log.info("User {} reviewed program {} with rating {}", reviewerId, programId, request.getOverallRating());

        return review;
    }

    /**
     * Récupère les avis d'un programme
     */
    @Transactional(readOnly = true)
    public Page<Review> getProgramReviews(UUID programId, Pageable pageable) {
        return reviewRepository.findByProgramIdOrderByCreatedAtDesc(programId, pageable);
    }

    /**
     * Récupère les avis donnés par un utilisateur
     */
    @Transactional(readOnly = true)
    public Page<Review> getUserReviews(UUID userId, Pageable pageable) {
        return reviewRepository.findByReviewerIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Vérifie si un utilisateur peut évaluer un programme
     */
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

        return conversationRepository.findDirectBetween(reviewerId, creatorId).isPresent();
    }

    private void validateCriteriaScores(Map<String, Integer> scores) {
        if (scores == null || scores.size() != 5) {
            throw new BusinessException("Les 5 critères doivent être évalués");
        }

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (!VALID_CRITERIA.contains(entry.getKey())) {
                throw new BusinessException("Critère invalide: " + entry.getKey());
            }
            Integer score = entry.getValue();
            if (score == null || score < 1 || score > 5) {
                throw new BusinessException("Les scores doivent être entre 1 et 5");
            }
        }

        // Check all criteria present
        for (String criterion : VALID_CRITERIA) {
            if (!scores.containsKey(criterion)) {
                throw new BusinessException("Critère manquant: " + criterion);
            }
        }
    }
}
