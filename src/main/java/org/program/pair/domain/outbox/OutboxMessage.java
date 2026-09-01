package org.program.pair.domain.outbox;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un message d'alerte à envoyer, posé en base avant de partir.
 *
 * <p><b>Pourquoi une table, et pas un pool en mémoire.</b> C'est la réponse à
 * l'exigence de « file dédiée haute priorité » du §7.2, et à ce qu'elle voulait
 * vraiment dire. Un exécuteur en mémoire perdrait ses envois en attente à chaque
 * redéploiement — sur Railway, un déploiement à 23 h 59 ferait disparaître une
 * alerte armée, en silence. Écrit en base <b>dans la même transaction que la
 * décision d'escalade</b>, le message survit au redémarrage, et son annulation —
 * quand la personne confirme pendant que le rappel se prépare — se fait dans la
 * même transaction que la clôture. C'est aussi ce qui rend l'ordre « SMS et
 * e-mail en parallèle » vrai : les deux lignes sont posées ensemble.
 *
 * <p><b>La priorité est une colonne.</b> Une alerte (priorité basse au sens
 * numérique) passe devant un e-mail de version longue. La « file dédiée » devient
 * ainsi une propriété de la table et de son index, pas d'un pool qui s'évapore.
 *
 * <p><b>Le corps porte un texte sensible</b> — un nom, un lieu, une heure — le
 * temps de l'envoi. Il n'a pas vocation à rester : une purge l'efface une fois le
 * message parti et un délai passé (comme les autres données du module, qui
 * expirent par défaut). {@code providerMessageId} est conservé pour recouper
 * l'accusé de remise et mesurer le SLO.
 */
@Entity
@Table(name = "outbox_messages")
@EntityListeners(AuditingEntityListener.class)
public class OutboxMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8)
    private OutboxChannel channel;

    /** Destinataire : un numéro E.164 pour un SMS, une adresse pour un e-mail. */
    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    /** Objet, pour un e-mail. Nul pour un SMS. */
    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Plus le nombre est petit, plus c'est prioritaire. Une alerte passe devant. */
    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** La veille à l'origine du message, pour recouper. Nul si sans objet. */
    @Column(name = "watch_id")
    private UUID watchId;

    /** Identifiant du message chez le fournisseur, une fois accepté. Pour l'accusé de remise. */
    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    /** Ce que le fournisseur rapporte sur la remise. UNKNOWN tant qu'aucun accusé n'est venu. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 12)
    private OutboxDelivery deliveryState = OutboxDelivery.UNKNOWN;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxMessage() {}

    public static OutboxMessage sms(String toE164, String body, int priority, UUID watchId) {
        OutboxMessage m = new OutboxMessage();
        m.channel = OutboxChannel.SMS;
        m.recipient = toE164;
        m.body = body;
        m.priority = priority;
        m.watchId = watchId;
        return m;
    }

    public static OutboxMessage email(String address, String subject, String html, int priority, UUID watchId) {
        OutboxMessage m = new OutboxMessage();
        m.channel = OutboxChannel.EMAIL;
        m.recipient = address;
        m.subject = subject;
        m.body = html;
        m.priority = priority;
        m.watchId = watchId;
        return m;
    }

    public UUID getId() { return id; }
    public OutboxChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public int getPriority() { return priority; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public UUID getWatchId() { return watchId; }
    public String getProviderMessageId() { return providerMessageId; }
    public OutboxDelivery getDeliveryState() { return deliveryState; }
    public void setDeliveryState(OutboxDelivery deliveryState) { this.deliveryState = deliveryState; }
    public Instant getSentAt() { return sentAt; }

    public void markSent(String providerMessageId, Instant when) {
        this.status = OutboxStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = when;
        this.lastAttemptAt = when;
        this.attempts++;
    }

    public void markAttemptFailed(Instant when, int maxAttempts) {
        this.attempts++;
        this.lastAttemptAt = when;
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
