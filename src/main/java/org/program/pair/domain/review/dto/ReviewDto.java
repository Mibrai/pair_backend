package org.program.pair.domain.review.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.review.Review;

import java.time.Instant;
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
    @io.swagger.v3.oas.annotations.media.Schema(deprecated = true, nullable = true,
        description = "Toujours null depuis le 14/09 (P-BL-10, D4) : plus de note sur un avis.")
    private Float score;
    private String comment;
    private Instant createdAt;
    @io.swagger.v3.oas.annotations.media.Schema(description = "« Tu le recommanderais ? Oui ». Même "
        + "visibilité que l'avis : l'organisateur et l'auteur.")
    private boolean recommend;

    public static ReviewDto fromEntity(Review review) {
        return ReviewDto.builder()
            .id(review.getId())
            .reviewerId(review.getReviewerId())
            .reviewerDisplayName(review.getReviewer() != null ? review.getReviewer().getDisplayName() : null)
            .programId(review.getProgramId())
            .programTitle(review.getProgram() != null ? review.getProgram().getTitle() : null)
            // Jamais rendue, y compris pour les notes antérieures encore en base.
            .score(null)
            .comment(review.getComment())
            .createdAt(review.getCreatedAt())
            .recommend(Boolean.TRUE.equals(review.getRecommend()))
            .build();
    }
}
