package org.program.pair.domain.user.dto;

import jakarta.validation.constraints.Pattern;

public record UpdatePrivacySettingsRequest(
    @Pattern(regexp = "PUBLIC|FRIENDS|PRIVATE", message = "Profile visibility must be PUBLIC, FRIENDS, or PRIVATE")
    String profileVisibility,
    Boolean showAge,
    Boolean showLastActive,
    Boolean showLocation,
    @Pattern(regexp = "EVERYONE|FRIENDS|NONE", message = "Allow messages must be EVERYONE, FRIENDS, or NONE")
    String allowMessages,
    Boolean showOnMap
) {}
