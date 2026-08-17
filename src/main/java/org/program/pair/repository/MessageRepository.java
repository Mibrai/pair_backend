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
     * <p>Sur les <b>fils de diffusion</b>, l'appartenance est dérivée des
     * inscriptions actives et de l'auteur du programme ; la ligne de membre n'y
     * porte que {@code lastReadAt} et ne donne aucun droit. D'où les deux formes
     * de la clause d'appartenance ci-dessous, et une jointure <b>externe</b> sur
     * {@code ConversationMember}.
     *
     * <p><b>Pourquoi externe.</b> Une jointure interne exigeait la ligne de
     * membre pour compter quoi que ce soit. Or sur un fil de diffusion cette
     * ligne n'est écrite qu'à la <i>première lecture</i> : la première annonce
     * arrivait donc chez des participants qui n'en avaient pas, et ne comptait
     * pour personne — ni au badge, ni dans le fil. L'auteur croyait avoir
     * prévenu son groupe, et le groupe ne voyait rien tant qu'il n'ouvrait pas
     * la messagerie de lui-même. C'était aussi vrai d'un nouvel inscrit, à qui
     * l'historique du fil est pourtant ouvert.
     *
     * <p>Ligne <b>absente</b> vaut donc « fil jamais ouvert », au même titre
     * qu'un {@code lastReadAt} nul : c'est ce que la ligne aurait dit.
     *
     * <p>L'appartenance dérivée reste une condition à part entière : un
     * participant parti ne garde pas au badge les messages d'un fil qu'il ne
     * peut plus ouvrir, faute de quoi il resterait avec un nombre qu'il lui
     * serait impossible de faire retomber.
     */
    @Query("""
        SELECT COUNT(m) FROM Message m
          JOIN m.conversation c
          LEFT JOIN ConversationMember cm
               ON cm.id.conversationId = c.id AND cm.id.userId = :userId
        WHERE m.sender.id <> :userId
          AND m.deletedAt IS NULL
          AND (cm.lastReadAt IS NULL OR m.sentAt > cm.lastReadAt)
          AND (
            (c.type <> org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST
             AND EXISTS (SELECT 1 FROM ConversationMember own
                         WHERE own.id.conversationId = c.id
                           AND own.id.userId = :userId))
            OR
            (c.type = org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST
             AND (EXISTS (SELECT 1 FROM UserProgram up
                          WHERE up.program.id = c.programId
                            AND up.user.id = :userId
                            AND up.status = org.program.pair.domain.program.UserProgramStatus.ACTIVE)
                  OR EXISTS (SELECT 1 FROM Program p
                             WHERE p.id = c.programId
                               AND p.userActivity.user.id = :userId)))
          )
        """)
    long countUnreadByUserId(@Param("userId") UUID userId);

    /**
     * Même compte, restreint à un fil : c'est {@code ConversationSummaryDto.unreadCount}.
     * La règle est identique à {@link #countUnreadByUserId(UUID)} à dessein — le
     * client somme ce champ, et une somme qui ne retombe pas sur le total ferait
     * diverger le badge selon la façon dont il est calculé.
     */
    @Query("""
        SELECT COUNT(m) FROM Message m
          JOIN m.conversation c
          LEFT JOIN ConversationMember cm
               ON cm.id.conversationId = c.id AND cm.id.userId = :userId
        WHERE c.id = :conversationId
          AND m.sender.id <> :userId
          AND m.deletedAt IS NULL
          AND (cm.lastReadAt IS NULL OR m.sentAt > cm.lastReadAt)
          AND (
            (c.type <> org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST
             AND EXISTS (SELECT 1 FROM ConversationMember own
                         WHERE own.id.conversationId = c.id
                           AND own.id.userId = :userId))
            OR
            (c.type = org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST
             AND (EXISTS (SELECT 1 FROM UserProgram up
                          WHERE up.program.id = c.programId
                            AND up.user.id = :userId
                            AND up.status = org.program.pair.domain.program.UserProgramStatus.ACTIVE)
                  OR EXISTS (SELECT 1 FROM Program p
                             WHERE p.id = c.programId
                               AND p.userActivity.user.id = :userId)))
          )
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
