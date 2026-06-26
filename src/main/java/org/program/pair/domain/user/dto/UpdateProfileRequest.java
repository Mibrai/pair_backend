package org.program.pair.domain.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @Size(max = 80) String displayName,
    @Size(max = 1000) String bio,
    Boolean locationPublic,
    Boolean onlineStatusVisible,
    Boolean receiveMessages,
    Integer blurRadiusM
) {}
