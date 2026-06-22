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
