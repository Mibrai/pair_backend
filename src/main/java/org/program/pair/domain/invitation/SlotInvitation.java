package org.program.pair.domain.invitation;

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
 * « Viens avec moi samedi. » Une invitation nominative, et ce qu'elle a produit.
 *
 * <p>Trois états se distinguent, et les confondre fausserait toute mesure :
 * l'invitation existe, elle a été acceptée par quelqu'un qui n'était pas encore
 * membre ({@code joinedAt}), et elle a mené cette personne sur le créneau
 * ({@code convertedAt}). Une invitation acceptée par un membre déjà inscrit a
 * marché sans recruter personne.
 */
@Entity
@Table(name = "slot_invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SlotInvitation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    /**
     * Le créneau visé. <b>Peut devenir nul</b> : la suppression du créneau met la
     * colonne à null plutôt que d'effacer la ligne, pour ne pas perdre la trace
     * d'une invitation qui a fait venir quelqu'un.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "invite_code", nullable = false, unique = true, length = 22)
    private String inviteCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_id")
    private User invitee;

    /** Renseigné seulement si l'invité n'était pas encore membre. */
    @Column(name = "joined_at")
    private Instant joinedAt;

    /** Renseigné quand l'invité a rejoint le créneau. */
    @Column(name = "converted_at")
    private Instant convertedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
