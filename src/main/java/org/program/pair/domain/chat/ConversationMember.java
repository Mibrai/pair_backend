package org.program.pair.domain.chat;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "user_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMember {

    @EmbeddedId
    @Builder.Default
    private ConversationMemberId id = new ConversationMemberId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("conversationId")
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    /**
     * En sourdine depuis cette date, ou jamais si nul.
     *
     * <p>La sourdine coupe <b>l'émission, pas la réception</b> — même règle que
     * {@code SubscriptionLevel.MUTED}. Le message arrive, il s'affiche dans le
     * fil ouvert, il compte dans le décompte du fil ; ce qu'il ne fait plus,
     * c'est sonner. Couper la réception ferait perdre des messages, ce que
     * personne ne demande en mettant une conversation en sourdine.
     */
    @Column(name = "muted_at")
    private Instant mutedAt;

    /**
     * Archivé depuis cette date, ou jamais si nul.
     *
     * <p><b>L'archivage ne se défait pas tout seul.</b> Un message reçu ne
     * ressort pas la conversation de l'archive, contrairement à ce que font
     * plusieurs messageries : ranger le fil dont on veut se débarrasser n'aurait
     * alors aucun effet, puisque c'est précisément celui qui reçoit. L'archive
     * est un classement délibéré, et la sourdine est le levier séparé pour le
     * silence — deux commandes qui font chacune une chose, plutôt qu'une seule
     * dont le comportement dépend de l'autre.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ConversationMemberId implements Serializable {
        private UUID conversationId;
        private UUID userId;
    }
}
