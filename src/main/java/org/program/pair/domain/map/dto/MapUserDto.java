package org.program.pair.domain.map.dto;

import java.util.List;
import java.util.UUID;

public record MapUserDto(
    UUID userId,
    String displayName,
    String avatarUrl,
    Double lat,
    Double lng,
    boolean isOnline,
    List<MapActivityBadgeDto> visibleActivities,
    String verificationStatus
) {}
