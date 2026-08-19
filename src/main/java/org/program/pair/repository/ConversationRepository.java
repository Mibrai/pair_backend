package org.program.pair.repository;

import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.chat.dto.ConversationContextDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Mes conversations.
     *
     * <p>Une conversation avec quelqu'un de bloqué disparaît <b>des deux côtés</b>,
     * et sans être supprimée : le blocage peut être levé, et l'historique doit
     * alors revenir tel quel. Le filtre porte sur les autres membres, jamais sur
     * moi-même — sans quoi une conversation de groupe s'évanouirait dès que l'un
     * de ses membres bloquerait n'importe qui.
     */
    @Query("SELECT c FROM Conversation c " +
           "WHERE EXISTS (" +
           "  SELECT 1 FROM ConversationMember cm " +
           "  WHERE cm.id.conversationId = c.id AND cm.id.userId = :userId) " +
           "  AND NOT EXISTS (" +
           "  SELECT 1 FROM ConversationMember other, UserBlock b " +
           "  WHERE other.id.conversationId = c.id AND other.id.userId <> :userId " +
           "    AND ((b.blocker.id = :userId AND b.blocked.id = other.id.userId) " +
           "      OR (b.blocker.id = other.id.userId AND b.blocked.id = :userId))) " +
           "ORDER BY c.createdAt DESC")
    List<Conversation> findByMemberId(@Param("userId") UUID userId);

    /** Fil de diffusion d'un programme, s'il a déjà été ouvert. */
    @Query("SELECT c FROM Conversation c " +
           "WHERE c.type = org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST " +
           "  AND c.programId = :programId")
    Optional<Conversation> findBroadcastByProgramId(@Param("programId") UUID programId);

    /**
     * Fils de diffusion auxquels {@code userId} a droit <b>en ce moment</b>.
     *
     * <p>Dérivé, jamais recopié : l'appartenance se lit sur les inscriptions
     * actives et sur l'auteur du programme, pas sur {@code conversation_members}.
     * C'est ce qui fait qu'un participant qui quitte le programme perd le fil et
     * son historique le jour même, et qu'un nouvel inscrit le gagne sans qu'on
     * ait rien à propager. Une liste recopiée à l'envoi divergerait de la
     * première inscription.
     *
     * <p>{@code conversation_members} garde un rôle, mais un seul : porter
     * {@code lastReadAt}, d'où sortent les non-lus.
     */
    @Query("SELECT c FROM Conversation c " +
           "WHERE c.type = org.program.pair.domain.chat.ConversationType.PROGRAM_BROADCAST " +
           "  AND (EXISTS (" +
           "        SELECT 1 FROM UserProgram up " +
           "        WHERE up.program.id = c.programId AND up.user.id = :userId " +
           "          AND up.status = org.program.pair.domain.program.UserProgramStatus.ACTIVE)" +
           "    OR EXISTS (" +
           "        SELECT 1 FROM Program p " +
           "        WHERE p.id = c.programId AND p.userActivity.user.id = :userId))")
    List<Conversation> findBroadcastsForMember(@Param("userId") UUID userId);

    /**
     * Contexte — programme, activité, créneau — des conversations demandées.
     *
     * <p>Une seule requête pour toute la liste : le contexte est réclamé par
     * chaque ligne de la messagerie, et le résoudre conversation par conversation
     * ajouterait trois allers par fil à un écran qui en compte déjà.
     *
     * <p>Jointures explicites sur {@code Program} et {@code Schedule} : la
     * conversation ne porte que leurs identifiants, sans relation JPA (voir
     * {@code Conversation.programId}). Toutes en {@code LEFT} — une conversation
     * née hors programme reste dans le résultat, avec un contexte vide.
     */
    @Query("SELECT new org.program.pair.domain.chat.dto.ConversationContextDto(" +
           "  c.id, p.id, p.title, a.name, s.id, s.startsAt, s.endsAt) " +
           "FROM Conversation c " +
           "LEFT JOIN c.activityContext a " +
           "LEFT JOIN Program p ON p.id = c.programId " +
           "LEFT JOIN Schedule s ON s.id = c.scheduleId " +
           "WHERE c.id IN :ids")
    List<ConversationContextDto> findContextsByIds(@Param("ids") Collection<UUID> ids);
}
