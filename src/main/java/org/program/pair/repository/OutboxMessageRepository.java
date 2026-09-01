package org.program.pair.repository;

import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /** Le lot à envoyer : les PENDING, du plus prioritaire au plus ancien. */
    List<OutboxMessage> findByStatusOrderByPriorityAscCreatedAtAsc(OutboxStatus status, Pageable page);

    /** Y a-t-il un message pour cette veille et ce canal ? Pour ne pas ré-escalader deux fois. */
    boolean existsByWatchIdAndChannel(UUID watchId, org.program.pair.domain.outbox.OutboxChannel channel);

    /** Purge des messages partis il y a assez longtemps — le corps ne doit pas s'attarder. */
    int deleteBySentAtBefore(Instant cutoff);

    /** Les messages d'une veille : la levée repart exactement là où l'alerte est allée. */
    List<OutboxMessage> findByWatchId(UUID watchId);

    /** Le message correspondant à un identifiant fournisseur — pour recouper un accusé de remise. */
    java.util.Optional<OutboxMessage> findByProviderMessageId(String providerMessageId);
}
