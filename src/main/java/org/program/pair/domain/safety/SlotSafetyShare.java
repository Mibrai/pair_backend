package org.program.pair.domain.safety;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un lien temporaire disant à un proche où l'on va, et jusqu'à quand.
 *
 * <p>La ligne ne stocke pas le contenu de la page : activité, lieu et
 * organisateur sont relus depuis le créneau à chaque ouverture, pour qu'un
 * changement de lieu se reflète immédiatement. Deux choses font exception et
 * sont figées à la création — l'échéance et la séance partagée — parce que le
 * rollover d'un créneau récurrent réécrit {@code starts_at} toutes les dix
 * minutes : les recalculer ferait fuir l'échéance devant nous et afficherait,
 * au proche, la date de la semaine suivante.
 */
@Entity
@Table(name = "slot_safety_shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SlotSafetyShare {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @Column(name = "share_token", nullable = false, unique = true, length = 22)
    private String shareToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "occurrence_starts_at", nullable = false)
    private Instant occurrenceStartsAt;

    @Column(name = "occurrence_ends_at", nullable = false)
    private Instant occurrenceEndsAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
