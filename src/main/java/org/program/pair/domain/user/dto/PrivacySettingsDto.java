package org.program.pair.domain.user.dto;

public record PrivacySettingsDto(
    String profileVisibility,
    Boolean showAge,
    Boolean showLastActive,
    Boolean showLocation,
    String allowMessages,
    Boolean showOnMap
) {}
