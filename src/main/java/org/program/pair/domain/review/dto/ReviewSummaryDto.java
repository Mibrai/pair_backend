package org.program.pair.domain.review.dto;

import java.util.List;
import java.util.UUID;

public record ReviewSummaryDto(
    UUID programId,
    @io.swagger.v3.oas.annotations.media.Schema(deprecated = true, nullable = true,
        description = "Toujours null depuis le 13/09 (P-BL-10) : plus de moyenne publique.")
    Double averageScore,
    long totalReviews,
    List<ReviewDto> recentReviews
) {}
