package org.program.pair.repository;

import org.program.pair.domain.chat.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId ORDER BY m.sentAt DESC")
    List<Message> findByConversationIdOrderBySentAtDesc(@Param("conversationId") UUID conversationId, int limit);

    Optional<Message> findFirstByConversationIdOrderBySentAtDesc(UUID conversationId);

    int countByConversationId(UUID conversationId);

    int countByConversationIdAndSentAtAfter(UUID conversationId, Instant sentAt);
}
