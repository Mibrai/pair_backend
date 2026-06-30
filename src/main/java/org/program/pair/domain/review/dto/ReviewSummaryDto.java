package org.program.pair.domain.review.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReviewSummaryDto(
    UUID programId,
    Double averageRating,
    long totalReviews,
    Map<String, Double> criteriaAverages,
    List<ReviewDto> recentReviews
) {}
