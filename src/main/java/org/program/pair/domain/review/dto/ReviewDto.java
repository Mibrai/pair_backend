package org.program.pair.domain.review.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.review.Review;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDto {
    private UUID id;
    private UUID reviewerId;
    private String reviewerDisplayName;
    private UUID programId;
    private String programTitle;
    private Integer overallRating;
    private Map<String, Integer> criteriaScores;
    private String comment;
    private Instant createdAt;

    public static ReviewDto fromEntity(Review review) {
        return ReviewDto.builder()
            .id(review.getId())
            .reviewerId(review.getReviewerId())
            .reviewerDisplayName(review.getReviewer() != null ? review.getReviewer().getDisplayName() : null)
            .programId(review.getProgramId())
            .programTitle(review.getProgram() != null ? review.getProgram().getTitle() : null)
            .overallRating(review.getOverallRating())
            .criteriaScores(review.getCriteriaScores())
            .comment(review.getComment())
            .createdAt(review.getCreatedAt())
            .build();
    }
}
