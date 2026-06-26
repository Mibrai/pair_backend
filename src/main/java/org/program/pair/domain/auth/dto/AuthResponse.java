package org.program.pair.domain.auth.dto;

import java.util.UUID;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    UUID userId,
    String displayName,
    String verificationStatus
) {}
