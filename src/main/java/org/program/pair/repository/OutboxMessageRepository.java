package org.program.pair.repository;

import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Le lot à envoyer : les messages de cet état <b>dont l'heure du prochain
     * essai est venue</b>, du plus prioritaire au plus ancien.
     *
     * <p><b>{@code nextAttemptAt} nul vaut « tout de suite ».</b> C'est le cas
     * d'un message qui vient d'être déposé — il ne doit pas attendre — et celui
     * des messages déjà en attente au moment du déploiement qui a ajouté la
     * colonne. Sans cette branche, la migration aurait gelé la file existante.
     *
     * <p>Le tri reste celui de l'index partiel {@code idx_outbox_a_envoyer} :
     * une alerte (priorité 0) passe devant un e-mail de vérification. Le filtre
     * sur la date est une condition résiduelle, évaluée sur les seules lignes
     * que cet index a déjà rapprochées.
     */
    @Query("""
        SELECT m FROM OutboxMessage m
         WHERE m.status = :status
           AND (m.nextAttemptAt IS NULL OR m.nextAttemptAt <= :now)
         ORDER BY m.priority ASC, m.createdAt ASC
        """)
    List<OutboxMessage> findAEnvoyer(@Param("status") OutboxStatus status,
                                     @Param("now") Instant now,
                                     Pageable page);

    /** Y a-t-il un message pour cette veille et ce canal ? Pour ne pas ré-escalader deux fois. */
    boolean existsByWatchIdAndChannel(UUID watchId, org.program.pair.domain.outbox.OutboxChannel channel);

    /** Purge des messages partis il y a assez longtemps — le corps ne doit pas s'attarder. */
    int deleteBySentAtBefore(Instant cutoff);

    /** Les messages d'une veille : la levée repart exactement là où l'alerte est allée. */
    List<OutboxMessage> findByWatchId(UUID watchId);

    /** Le message correspondant à un identifiant fournisseur — pour recouper un accusé de remise. */
    java.util.Optional<OutboxMessage> findByProviderMessageId(String providerMessageId);
}
