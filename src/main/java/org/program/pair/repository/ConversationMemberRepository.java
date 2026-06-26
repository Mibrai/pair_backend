package org.program.pair.repository;

import org.program.pair.domain.chat.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMember.ConversationMemberId> {

    @Query("SELECT cm.id.userId FROM ConversationMember cm WHERE cm.id.conversationId = :conversationId")
    List<UUID> findUserIdsByConversationId(@Param("conversationId") UUID conversationId);

    @Query("SELECT cm FROM ConversationMember cm WHERE cm.id.conversationId = :conversationId AND cm.id.userId = :userId")
    Optional<ConversationMember> findByConversationIdAndUserId(
        @Param("conversationId") UUID conversationId,
        @Param("userId") UUID userId);

    @Query("SELECT CASE WHEN COUNT(cm) > 0 THEN true ELSE false END FROM ConversationMember cm WHERE cm.id.conversationId = :conversationId AND cm.id.userId = :userId")
    boolean existsByConversationIdAndUserId(
        @Param("conversationId") UUID conversationId,
        @Param("userId") UUID userId);
}
