package org.program.pair.domain.recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationStatsDto {
    private long recommendationsReceivedCount;
    private long recommendationsGivenCount;
    private Double averageRating;
    private long uniqueRecommenders;
}
