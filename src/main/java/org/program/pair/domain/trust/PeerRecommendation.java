package org.program.pair.domain.trust;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "peer_recommendations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"from_user_id", "to_user_id"}),
    indexes = {
        @Index(name = "idx_peer_rec_to", columnList = "to_user_id"),
        @Index(name = "idx_peer_rec_from", columnList = "from_user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeerRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interaction_proof_id", nullable = false)
    private Conversation interactionProof;

    @Column(length = 500)
    @Size(max = 500)
    private String comment;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
