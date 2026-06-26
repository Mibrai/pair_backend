package org.program.pair.domain.badge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.trust.BadgeAward;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeAwardDto {
    private UUID id;
    private UUID userId;
    private BadgeDto badge;
    private Instant awardedAt;

    public static BadgeAwardDto fromEntity(BadgeAward award) {
        return BadgeAwardDto.builder()
            .id(award.getId() != null ? award.getId().getBadgeId() : null)
            .userId(award.getId() != null ? award.getId().getUserId() : null)
            .badge(award.getBadge() != null ? BadgeDto.fromEntity(award.getBadge()) : null)
            .awardedAt(award.getAwardedAt())
            .build();
    }
}
