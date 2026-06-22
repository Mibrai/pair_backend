package org.program.pair.domain.trust;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "badge_awards",
    uniqueConstraints = @UniqueConstraint(columnNames = {"badge_id", "user_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BadgeAward {

    @EmbeddedId
    @Builder.Default
    private BadgeAwardId id = new BadgeAwardId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("badgeId")
    @JoinColumn(name = "badge_id")
    private Badge badge;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "awarded_at", nullable = false)
    @Builder.Default
    private Instant awardedAt = Instant.now();

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class BadgeAwardId implements Serializable {
        private UUID badgeId;
        private UUID userId;
    }
}
