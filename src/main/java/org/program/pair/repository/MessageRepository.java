package org.program.pair.repository;

import org.program.pair.domain.chat.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId ORDER BY m.sentAt DESC")
    List<Message> findByConversationIdOrderBySentAtDesc(@Param("conversationId") UUID conversationId, int limit);

    Optional<Message> findFirstByConversationIdOrderBySentAtDesc(UUID conversationId);

    /**
     * Messages non lus d'un utilisateur, <b>tous fils confondus</b>. C'est la
     * moitié « messagerie » du badge d'icône, et la valeur servie par
     * {@code GET /api/conversations/unread-count}.
     *
     * <p>Trois exclusions, qu'un simple {@code sentAt > lastReadAt} ne faisait
     * pas et qui gonflaient le compte :
     *
     * <ul>
     *   <li><b>ses propres messages</b> — envoyer n'est pas recevoir. Un fil où
     *       l'on vient d'écrire trois fois affichait trois non lus ;</li>
     *   <li><b>les messages supprimés</b> ({@code deletedAt}), qui ne s'affichent
     *       plus qu'en « [Message supprimé] » ;</li>
     *   <li><b>les messages d'un expéditeur anonymisé</b> (purge RGPD, {@code sender}
     *       nul) — la comparaison sur {@code sender.id} les écarte d'elle-même.</li>
     * </ul>
     *
     * <p>{@code lastReadAt} nul signifie « fil jamais ouvert » : tout ce que les
     * autres y ont écrit est non lu.
     *
     * <p>Une quatrième exclusion vise les <b>fils de diffusion</b>, dont
     * l'appartenance est dérivée des inscriptions actives : la ligne de membre y
     * porte {@code lastReadAt} mais ne donne aucun droit. Sans cette clause, un
     * participant parti garderait au badge les messages d'un fil qu'il ne peut
     * plus ouvrir — un nombre qu'il lui serait impossible de faire retomber.
     */
    @Query("""
        SELECT COUNT(m) FROM Message m, ConversationMember cm
        WHERE cm.conversation.id = m.conversation.id
          AND cm.user.id = :userId
          AND m.sender.id <> :userId
          AND m.deletedAt IS NULL
          AND (cm.lastReadAt IS NULL OR m.sentAt > cm.lastReadAt)
          AND (cm.conversation.type <> org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST
               OR EXISTS (SELECT 1 FROM UserProgram up
                          WHERE up.program.id = cm.conversation.programId
                            AND up.user.id = :userId
                            AND up.status = org.program.pair.domain.program.UserProgramStatus.ACTIVE)
               OR EXISTS (SELECT 1 FROM Program p
                          WHERE p.id = cm.conversation.programId
                            AND p.userActivity.user.id = :userId))
        """)
    long countUnreadByUserId(@Param("userId") UUID userId);

    /**
     * Même compte, restreint à un fil : c'est {@code ConversationSummaryDto.unreadCount}.
     * La règle est identique à {@link #countUnreadByUserId(UUID)} à dessein — le
     * client somme ce champ, et une somme qui ne retombe pas sur le total ferait
     * diverger le badge selon la façon dont il est calculé.
     */
    @Query("""
        SELECT COUNT(m) FROM Message m, ConversationMember cm
        WHERE cm.conversation.id = m.conversation.id
          AND cm.user.id = :userId
          AND cm.conversation.id = :conversationId
          AND m.sender.id <> :userId
          AND m.deletedAt IS NULL
          AND (cm.lastReadAt IS NULL OR m.sentAt > cm.lastReadAt)
        """)
    int countUnreadByUserIdAndConversationId(@Param("userId") UUID userId,
                                             @Param("conversationId") UUID conversationId);

    /**
     * Find messages sent by user (for GDPR export)
     */
    List<Message> findBySenderId(UUID senderId);

    /**
     * Anonymize messages for GDPR purge (Article 17)
     * Replace sender info with anonymous placeholder
     */
    @Modifying
    @Query("UPDATE Message m SET m.sender = null, m.content = '[Message supprimé]' WHERE m.sender.id = :userId")
    void anonymizeBySenderId(@Param("userId") UUID userId);
}
