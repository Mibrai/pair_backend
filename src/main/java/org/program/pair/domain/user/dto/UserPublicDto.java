package org.program.pair.domain.user.dto;

import java.util.List;
import java.util.UUID;

public record UserPublicDto(
    UUID id,
    String displayName,
    String bio,
    String avatarUrl,
    String verificationStatus,
    List<String> badgeCodes,
    List<UserActivitySummaryDto> activities,
    boolean isOnline
) {}
