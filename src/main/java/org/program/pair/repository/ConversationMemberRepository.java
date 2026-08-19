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

    /**
     * Ceux qui ont mis ce fil en sourdine.
     *
     * <p>Interrogé à chaque envoi, pour retirer ces destinataires de la
     * <b>push</b> — et d'elle seule. Le WebSocket part quand même : une
     * application ouverte sur le fil doit voir le message arriver, la sourdine ne
     * demandant pas de perdre des messages mais de ne pas sonner.
     *
     * <p>Ne rend que ceux qui ont une ligne : sur un fil de diffusion, une ligne
     * absente veut dire « jamais ouvert », donc jamais mis en sourdine.
     */
    @Query("SELECT cm.id.userId FROM ConversationMember cm "
        + "WHERE cm.id.conversationId = :conversationId AND cm.mutedAt IS NOT NULL")
    List<UUID> findMutedUserIdsByConversationId(@Param("conversationId") UUID conversationId);

    /**
     * Find all conversations for a user (for GDPR export)
     */
    @Query("SELECT cm.conversation FROM ConversationMember cm WHERE cm.id.userId = :userId")
    List<org.program.pair.domain.chat.Conversation> findConversationsByUserId(@Param("userId") UUID userId);
}
