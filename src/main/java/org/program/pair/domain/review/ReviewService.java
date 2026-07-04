package org.program.pair.domain.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.review.dto.CreateReviewRequest;
import org.program.pair.domain.review.dto.ReviewDto;
import org.program.pair.domain.review.dto.ReviewSummaryDto;
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
    private final ProgramRepository programRepository;

    public Review createReview(UUID reviewerId, CreateReviewRequest request) {
        UUID programId = request.getProgramId();

        var program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme non trouvé"));

        UUID creatorId = program.getUserActivity() != null && program.getUserActivity().getUser() != null
            ? program.getUserActivity().getUser().getId()
            : null;

        if (creatorId == null) {
            throw new BusinessException("Programme sans créateur identifié");
        }

        if (reviewerId.equals(creatorId)) {
            throw new BusinessException("Vous ne pouvez pas évaluer votre propre programme");
        }

        if (reviewRepository.findByReviewerIdAndProgramId(reviewerId, programId).isPresent()) {
            throw new BusinessException("Vous avez déjà évalué ce programme");
        }

        UUID conversationId = conversationRepository.findDirectBetween(reviewerId, creatorId)
            .map(c -> c.getId())
            .orElseThrow(() -> new BusinessException(
                "Vous devez avoir échangé des messages avec le créateur du programme avant de pouvoir l'évaluer"
            ));

        Review review = Review.builder()
            .id(UUID.randomUUID())
            .reviewerId(reviewerId)
            .programId(programId)
            .interactionProofId(conversationId)
            .score(request.getScore())
            .comment(request.getComment())
            .build();

        review = reviewRepository.save(review);
        log.info("User {} reviewed program {} with score {}", reviewerId, programId, request.getScore());

        return review;
    }

    @Transactional(readOnly = true)
    public Page<Review> getProgramReviews(UUID programId, Pageable pageable) {
        return reviewRepository.findByProgramIdOrderByCreatedAtDesc(programId, pageable);
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

        return conversationRepository.findDirectBetween(reviewerId, creatorId).isPresent();
    }

    @Transactional(readOnly = true)
    public ReviewSummaryDto getProgramReviewSummary(UUID programId) {
        long total = reviewRepository.countByProgramId(programId);
        Double avg = reviewRepository.findAverageRatingByProgramId(programId);

        List<ReviewDto> recent = reviewRepository
            .findByProgramIdOrderByCreatedAtDesc(programId, PageRequest.of(0, 5))
            .stream()
            .map(ReviewDto::fromEntity)
            .toList();

        return new ReviewSummaryDto(programId, avg, total, recent);
    }
}
