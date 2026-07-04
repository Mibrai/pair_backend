package org.program.pair.domain.review.dto;

import java.util.List;
import java.util.UUID;

public record ReviewSummaryDto(
    UUID programId,
    Double averageScore,
    long totalReviews,
    List<ReviewDto> recentReviews
) {}
