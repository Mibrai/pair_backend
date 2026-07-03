package org.program.pair.domain.chat;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "message_edit_history", indexes = {
    @Index(name = "idx_edit_history_message", columnList = "message_id"),
    @Index(name = "idx_edit_history_edited_at", columnList = "edited_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageEditHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "previous_content", nullable = false, length = 4000)
    private String previousContent;

    @Column(name = "edited_at", nullable = false)
    @Builder.Default
    private Instant editedAt = Instant.now();
}
