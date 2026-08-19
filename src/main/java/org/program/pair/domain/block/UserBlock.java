package org.program.pair.domain.block;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Untel a bloqué untel.
 *
 * <p>Le fait est enregistré dans un seul sens ; il s'applique dans les deux.
 * Conserver l'asymétrie est nécessaire pour débloquer — seul celui qui a bloqué
 * peut le défaire — mais aucune surface de lecture ne doit s'en servir pour
 * distinguer les deux personnes : du point de vue de la visibilité, elles
 * n'existent plus l'une pour l'autre.
 */
@Entity
@Table(name = "user_blocks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class UserBlock {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    /**
     * Motif facultatif, à usage de modération. <b>Jamais rendu à la personne
     * bloquée</b>, ni à personne d'autre que le bloqueur : le blocage doit rester
     * indétectable, et un motif qui fuite le rend détectable.
     */
    @Column(name = "reason", length = 30)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
