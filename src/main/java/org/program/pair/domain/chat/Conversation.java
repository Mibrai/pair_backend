package org.program.pair.domain.chat;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.activity.Activity;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conv_last_message", columnList = "last_message_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // length = 30 : PROGRAM_BROADCAST en fait 17, la colonne d'origine en
    // acceptait 10. Voir V53.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ConversationType type = ConversationType.DIRECT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_context_id")
    private Activity activityContext;

    /**
     * Programme dont la conversation tire son contexte, s'il y en a un.
     *
     * <p>Identifiant nu plutôt que {@code @ManyToOne} : le paquet {@code program}
     * dépend déjà de {@code chat} ({@code SlotService} ouvre une conversation en
     * rejoignant un créneau), et une relation le ferait dépendre en retour. Même
     * choix que {@code PeerRecommendation.activityContext}. La lecture se fait
     * par jointure explicite dans {@code ConversationRepository}.
     */
    @Column(name = "program_id")
    private UUID programId;

    /**
     * Séance qui lie les membres — la date que le client compare à maintenant
     * pour griser un fil dont le créneau est passé. Voir {@link #programId} pour
     * l'absence de relation.
     */
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    // Note: No bidirectional mapping for messages and members to avoid complexity
    // Use repositories to query them directly
}
