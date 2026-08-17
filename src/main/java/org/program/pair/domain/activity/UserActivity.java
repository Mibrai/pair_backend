package org.program.pair.domain.activity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_activities",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "activity_id"}),
    indexes = {
        @Index(name = "idx_ua_user", columnList = "user_id"),
        @Index(name = "idx_ua_activity", columnList = "activity_id"),
        @Index(name = "idx_ua_visible", columnList = "visible_on_map")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @Column(name = "visible_on_map", nullable = false)
    @Builder.Default
    private Boolean visibleOnMap = true;

    @Column(name = "custom_description", length = 500)
    @Size(max = 500)
    private String customDescription;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private ActivityLevel level = ActivityLevel.ANY;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    @Builder.Default
    private ActivityFormat format = ActivityFormat.ANY;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Instant où les abonnés de la catégorie ont été prévenus de cette activité,
     * nul tant qu'ils ne l'ont pas été.
     *
     * <p>Porte l'unicité de l'annonce, et non un décompte des créneaux : le
     * premier créneau supprimé puis reposé ferait du suivant « le premier » une
     * seconde fois. Même forme que {@code Program.subscribersNotifiedAt}, pour
     * la même raison.
     *
     * <p>Renseigné à la main, sans annotation d'audit : c'est la date d'un
     * <b>fait métier</b>, pas celle de la ligne.
     */
    @Column(name = "category_notified_at")
    private Instant categoryNotifiedAt;
}
