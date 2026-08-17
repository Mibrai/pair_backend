package org.program.pair.domain.subscription;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_sub_subscriber", columnList = "subscriber_id"),
    @Index(name = "idx_sub_target_author", columnList = "target_author_id"),
    @Index(name = "idx_sub_target_user_activity", columnList = "target_user_activity_id"),
    @Index(name = "idx_sub_target_category", columnList = "target_category_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private User subscriber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_author_id")
    private User targetAuthor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_activity_id")
    private UserActivity targetUserActivity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_category_id")
    private Category targetCategory;

    /**
     * Volume de cet abonnement. Jamais nul : {@code ALL} pour toute ligne
     * antérieure à la V58, par défaut de colonne.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubscriptionLevel level = SubscriptionLevel.ALL;

    /**
     * Portée géographique, sur les seuls abonnements {@code CATEGORY}.
     *
     * <p>Les trois champs valent ensemble ou pas du tout — la contrainte
     * {@code chk_subscription_scope} l'impose en base. Absents : aucune
     * contrainte géographique, comportement d'avant la V58.
     *
     * <p>Le rayon est en <b>mètres</b>, comme {@code /search} et
     * {@code /slots/feed}.
     */
    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    @Column(name = "radius_meters")
    private Integer radiusMeters;

    /** Vrai quand cet abonnement porte une portée géographique exploitable. */
    public boolean hasScope() {
        return lat != null && lng != null && radiusMeters != null;
    }

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
