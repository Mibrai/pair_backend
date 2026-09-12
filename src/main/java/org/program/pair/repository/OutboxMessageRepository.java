package org.program.pair.repository;

import org.program.pair.domain.outbox.MessageAEnvoyer;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * De quoi remettre au fournisseur le message qu'on vient de réclamer, et
     * rien de plus.
     *
     * <p><b>La sélection du lot ne se fait plus ici.</b> Elle se fait en SQL, par
     * {@code OutboxClaimer}, parce qu'elle doit poser un verrou
     * ({@code FOR UPDATE SKIP LOCKED}) et rendre les identifiants retenus
     * ({@code RETURNING}) — deux choses que JPA ne sait pas faire sous
     * {@code @Modifying}. Ce dépôt ne sert plus qu'à relire, un par un, ce que la
     * réclamation a retenu.
     *
     * <p><b>Une projection, et pas l'entité</b> : cette lecture a lieu hors
     * transaction, et {@code open-in-view} ne couvre pas les jobs. Voir
     * {@link MessageAEnvoyer}.
     *
     * <p>L'état est passé en paramètre plutôt que codé : l'appelant ne veut que
     * les messages qu'il a lui-même réclamés, c'est-à-dire
     * {@link OutboxStatus#SENDING}. Un message qui n'y serait plus — purgé,
     * confirmé entre-temps par un balayage qui avait pris le relais après
     * expiration du verrou — ne doit surtout pas être renvoyé, et l'absence de
     * résultat est ce qui l'en empêche.
     */
    @Query("""
        SELECT new org.program.pair.domain.outbox.MessageAEnvoyer(
                   m.id, m.channel, m.recipient, m.subject, m.body)
          FROM OutboxMessage m
         WHERE m.id = :id
           AND m.status = :status
        """)
    Optional<MessageAEnvoyer> findAEnvoyer(@Param("id") UUID id,
                                           @Param("status") OutboxStatus status);

    /** Y a-t-il un message pour cette veille et ce canal ? Pour ne pas ré-escalader deux fois. */
    boolean existsByWatchIdAndChannel(UUID watchId, org.program.pair.domain.outbox.OutboxChannel channel);

    /** Purge des messages partis il y a assez longtemps — le corps ne doit pas s'attarder. */
    int deleteBySentAtBefore(Instant cutoff);

    /** Les messages d'une veille : la levée repart exactement là où l'alerte est allée. */
    List<OutboxMessage> findByWatchId(UUID watchId);

    /** Le message correspondant à un identifiant fournisseur — pour recouper un accusé de remise. */
    java.util.Optional<OutboxMessage> findByProviderMessageId(String providerMessageId);
}
