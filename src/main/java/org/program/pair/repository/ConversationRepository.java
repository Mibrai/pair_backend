package org.program.pair.repository;

import org.program.pair.domain.chat.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c " +
           "WHERE c.type = 'DIRECT' AND c.id IN (" +
           "  SELECT cm1.id.conversationId FROM ConversationMember cm1 " +
           "  WHERE cm1.id.userId = :userId1 AND cm1.id.conversationId IN (" +
           "    SELECT cm2.id.conversationId FROM ConversationMember cm2 " +
           "    WHERE cm2.id.userId = :userId2))")
    Optional<Conversation> findDirectBetween(
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );

    @Query("SELECT c FROM Conversation c " +
           "WHERE c.id = :convId AND EXISTS (" +
           "  SELECT 1 FROM ConversationMember cm " +
           "  WHERE cm.id.conversationId = c.id AND cm.id.userId = :userId)")
    Optional<Conversation> findByIdAndMemberId(
        @Param("convId") UUID convId,
        @Param("userId") UUID userId
    );

    @Query("SELECT c FROM Conversation c " +
           "WHERE c.id = :convId AND EXISTS (" +
           "  SELECT 1 FROM ConversationMember cm1 " +
           "  WHERE cm1.id.conversationId = c.id AND cm1.id.userId = :userId1) " +
           "AND EXISTS (" +
           "  SELECT 1 FROM ConversationMember cm2 " +
           "  WHERE cm2.id.conversationId = c.id AND cm2.id.userId = :userId2)")
    Optional<Conversation> findByIdAndBothMembers(
        @Param("convId") UUID convId,
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Conversation c " +
           "WHERE c.id IN (" +
           "  SELECT cm1.id.conversationId FROM ConversationMember cm1 " +
           "  WHERE cm1.id.userId = :userId1 AND cm1.id.conversationId IN (" +
           "    SELECT cm2.id.conversationId FROM ConversationMember cm2 " +
           "    WHERE cm2.id.userId = :userId2))")
    boolean existsBetweenUsers(
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );

    @Query("SELECT c FROM Conversation c " +
           "WHERE EXISTS (" +
           "  SELECT 1 FROM ConversationMember cm " +
           "  WHERE cm.id.conversationId = c.id AND cm.id.userId = :userId) " +
           "ORDER BY c.createdAt DESC")
    List<Conversation> findByMemberId(@Param("userId") UUID userId);
}
