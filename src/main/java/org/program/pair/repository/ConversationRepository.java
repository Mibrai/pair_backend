package org.program.pair.repository;

import org.program.pair.domain.chat.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c JOIN c.members m1 JOIN c.members m2 " +
           "WHERE c.type = 'DIRECT' AND m1.user.id = :userId1 AND m2.user.id = :userId2")
    Optional<Conversation> findDirectBetween(
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );

    @Query("SELECT c FROM Conversation c JOIN c.members m WHERE c.id = :convId AND m.user.id = :userId")
    Optional<Conversation> findByIdAndMemberId(
        @Param("convId") UUID convId,
        @Param("userId") UUID userId
    );

    @Query("SELECT c FROM Conversation c JOIN c.members m1 JOIN c.members m2 " +
           "WHERE c.id = :convId AND m1.user.id = :userId1 AND m2.user.id = :userId2")
    Optional<Conversation> findByIdAndBothMembers(
        @Param("convId") UUID convId,
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Conversation c " +
           "JOIN c.members m1 JOIN c.members m2 " +
           "WHERE m1.user.id = :userId1 AND m2.user.id = :userId2")
    boolean existsBetweenUsers(
        @Param("userId1") UUID userId1,
        @Param("userId2") UUID userId2
    );
}
