package org.program.pair.domain.outbox;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
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

    /**
     * Le compte concerné, pour les messages qui en visent un. Nul pour une
     * alerte de veille, dont le destinataire est un proche sans compte meetDo.
     */
    @Column(name = "user_id")
    private UUID userId;

    /** À quoi sert ce message — c'est lui qui dit quoi faire de son accusé de remise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private OutboxPurpose purpose = OutboxPurpose.WATCH_ALERT;

    /** Identifiant du message chez le fournisseur, une fois accepté. Pour l'accusé de remise. */
    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    /** Ce que le fournisseur rapporte sur la remise. UNKNOWN tant qu'aucun accusé n'est venu. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 12)
    private OutboxDelivery deliveryState = OutboxDelivery.UNKNOWN;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /**
     * À partir de quand ce message redevient éligible au balayage.
     *
     * <p>Nul veut dire « jamais essayé » : un message tout juste déposé part au
     * premier passage, sans attendre. Après un refus, la date est repoussée d'un
     * délai qui double à chaque essai — c'est ce qui fait qu'une panne
     * fournisseur de quelques minutes n'épuise pas les essais d'une alerte.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Premier délai après un refus. Il double ensuite à chaque essai. */
    private static final Duration DELAI_INITIAL = Duration.ofSeconds(30);

    /**
     * Plafond du délai entre deux essais.
     *
     * <p>Sans plafond, le doublement mettrait le dixième essai à plus de quatre
     * heures : un message d'alerte remis si tard ne vaut plus rien, et la file
     * garderait son corps sensible d'autant plus longtemps.
     */
    private static final Duration DELAI_MAX = Duration.ofMinutes(30);

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

    /**
     * L'e-mail de vérification d'une adresse.
     *
     * <p>Il passe par l'outbox depuis le lot du 07/09, et c'est tout l'objet de
     * ce lot : c'était le seul de nos courriers à partir par un appel direct,
     * donc le seul dont l'identifiant Resend n'était pas conservé, donc le seul
     * dont le rebond n'était rapporté à personne. Il y gagne trois choses qu'il
     * n'avait pas — la durabilité au redéploiement, les essais, et un envoi qui
     * ne se fait plus dans la transaction d'inscription.
     *
     * <p><b>Le corps est composé par l'appelant</b>, sur le fil de la requête,
     * et non ici : c'est là, et seulement là, que la langue demandée par
     * l'appareil est encore connue.
     */
    public static OutboxMessage verificationEmail(UUID userId, String address,
                                                  String subject, String html, int priority) {
        OutboxMessage m = new OutboxMessage();
        m.channel = OutboxChannel.EMAIL;
        m.purpose = OutboxPurpose.EMAIL_VERIFICATION;
        m.recipient = address;
        m.subject = subject;
        m.body = html;
        m.priority = priority;
        m.userId = userId;
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
    public UUID getUserId() { return userId; }
    public OutboxPurpose getPurpose() { return purpose; }
    public String getProviderMessageId() { return providerMessageId; }
    public OutboxDelivery getDeliveryState() { return deliveryState; }
    public void setDeliveryState(OutboxDelivery deliveryState) { this.deliveryState = deliveryState; }
    public Instant getSentAt() { return sentAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }

    public void markSent(String providerMessageId, Instant when) {
        this.status = OutboxStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = when;
        this.lastAttemptAt = when;
        this.attempts++;
        // Un message parti après deux refus n'a plus de prochain essai à attendre :
        // la date laissée par ces refus n'a plus de sens, on l'efface.
        this.nextAttemptAt = null;
    }

    /**
     * Enregistre un essai qui n'a pas abouti, et fixe quand le suivant aura lieu.
     *
     * <p><b>Le délai croît, et c'est tout l'objet.</b> Le balayage passe toutes
     * les dix secondes ; sans date de prochain essai, cinq essais s'épuisaient en
     * moins d'une minute et une panne fournisseur d'une minute suffisait à
     * déclarer une alerte définitivement en échec. Le délai vaut donc
     * {@code 30 s × 2^(essais-1)}, plafonné à {@link #DELAI_MAX} : 30 s, 1 min,
     * 2 min, 4, 8, 16, puis 30 min. Sur dix essais, cela couvre un peu plus de
     * deux heures de panne.
     *
     * <p>Au dernier essai, le message passe {@code FAILED} — un état terminal,
     * fait pour être vu. On ne lui pose pas de nouvelle date : il n'y aura pas
     * d'essai suivant, et {@code nextAttemptAt} garde celle du refus précédent,
     * déjà passée, pour qu'une remise à {@code PENDING} à la main reparte
     * immédiatement.
     *
     * @param when        l'instant de l'essai
     * @param maxAttempts le nombre d'essais au-delà duquel on renonce
     */
    public void markAttemptFailed(Instant when, int maxAttempts) {
        this.attempts++;
        this.lastAttemptAt = when;
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.nextAttemptAt = when.plus(delaiAvantProchainEssai(this.attempts));
    }

    /**
     * Le délai à attendre après {@code essais} refus : {@code 30 s × 2^(essais-1)},
     * plafonné à trente minutes.
     *
     * <p>L'exposant est borné avant le décalage : un nombre d'essais élevé — que
     * produirait une remise à {@code PENDING} à la main sur un message épuisé —
     * ferait sinon repasser le décalage par zéro, et rendrait le message
     * éligible immédiatement, en boucle.
     */
    static Duration delaiAvantProchainEssai(int essais) {
        int exposant = Math.min(Math.max(essais - 1, 0), 20);
        long secondes = DELAI_INITIAL.toSeconds() << exposant;
        return secondes >= DELAI_MAX.toSeconds() ? DELAI_MAX : Duration.ofSeconds(secondes);
    }
}
