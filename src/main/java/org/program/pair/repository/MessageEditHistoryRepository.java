package org.program.pair.repository;

import org.program.pair.domain.chat.MessageEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageEditHistoryRepository extends JpaRepository<MessageEditHistory, UUID> {

    List<MessageEditHistory> findByMessageIdOrderByEditedAtDesc(UUID messageId);
}
