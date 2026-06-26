package org.program.pair.domain.badge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.trust.Badge;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeDto {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private String iconUrl;
    private String conditionType;
    private Integer conditionThreshold;

    public static BadgeDto fromEntity(Badge badge) {
        return BadgeDto.builder()
            .id(badge.getId())
            .code(badge.getCode())
            .name(badge.getLabel())  // label in entity
            .description(badge.getLabel())  // no description field, use label
            .iconUrl(badge.getIcon())  // icon in entity
            .conditionType(badge.getConditionType() != null ? badge.getConditionType().name() : null)
            .conditionThreshold(badge.getConditionThreshold())
            .build();
    }
}
