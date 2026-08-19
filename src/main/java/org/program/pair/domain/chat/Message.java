package org.program.pair.domain.chat;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_messages_conversation", columnList = "conversation_id"),
    @Index(name = "idx_messages_sender", columnList = "sender_id"),
    @Index(name = "idx_messages_sent_at", columnList = "sent_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(nullable = false, length = 4000)
    @NotBlank
    @Size(max = 4000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private MessageStatus status = MessageStatus.SENT;

    @Column(name = "sent_at", nullable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Position partagée ponctuellement, et son échéance.
     *
     * <p>Les trois vont ensemble ou pas du tout, la base le contraint. Le point
     * est capturé à l'envoi et ne se met jamais à jour : partager sa position
     * deux fois, c'est envoyer deux messages, tous deux visibles dans le fil.
     *
     * <p><b>Lire ces champs ne suffit pas</b> — il faut aussi vérifier
     * {@code locationExpiresAt}. Un balayage efface les coordonnées échues, mais
     * c'est la lecture qui fait foi : entre l'échéance et le passage du balayage,
     * les colonnes portent encore un point qu'il ne faut plus servir.
     */
    @Column(name = "location_lat")
    private Double locationLat;

    @Column(name = "location_lng")
    private Double locationLng;

    @Column(name = "location_expires_at")
    private Instant locationExpiresAt;
}
