package org.program.pair.domain.recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.recommendation.PeerRecommendation;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeerRecommendationDto {
    private UUID id;
    private UUID recommenderId;
    private String recommenderDisplayName;
    private UUID recommendedId;
    private String recommendedDisplayName;
    private Integer rating;
    private String comment;
    private UUID activityContext;
    private String activityName;
    private UUID programContext;
    private String programTitle;
    private Instant createdAt;

    public static PeerRecommendationDto fromEntity(PeerRecommendation rec) {
        return PeerRecommendationDto.builder()
            .id(rec.getId())
            .recommenderId(rec.getRecommenderId())
            .recommenderDisplayName(rec.getRecommender() != null ? rec.getRecommender().getDisplayName() : null)
            .recommendedId(rec.getRecommendedId())
            .recommendedDisplayName(rec.getRecommended() != null ? rec.getRecommended().getDisplayName() : null)
            .rating(rec.getRating())
            .comment(rec.getComment())
            .activityContext(rec.getActivityContext())
            .activityName(rec.getActivity() != null ? rec.getActivity().getName() : null)
            .programContext(rec.getProgramContext())
            .programTitle(rec.getProgram() != null ? rec.getProgram().getTitle() : null)
            .createdAt(rec.getCreatedAt())
            .build();
    }
}
