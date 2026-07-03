package org.program.pair.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrivacySettings {

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_visibility", length = 20)
    @Builder.Default
    private ProfileVisibility profileVisibility = ProfileVisibility.PUBLIC;

    @Column(name = "show_age")
    @Builder.Default
    private Boolean showAge = true;

    @Column(name = "show_last_active")
    @Builder.Default
    private Boolean showLastActive = true;

    @Column(name = "show_location")
    @Builder.Default
    private Boolean showLocation = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "allow_messages", length = 20)
    @Builder.Default
    private MessagePermission allowMessages = MessagePermission.EVERYONE;

    @Column(name = "show_on_map")
    @Builder.Default
    private Boolean showOnMap = false;
}
