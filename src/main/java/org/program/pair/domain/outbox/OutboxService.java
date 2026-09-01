package org.program.pair.domain.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.sms.SmsService;
import org.program.pair.repository.OutboxMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L'outbox : y déposer un message d'alerte, et vider ce qui attend.
 *
 * <p>Le dépôt ({@code enqueue*}) se fait dans la transaction de l'appelant — la
 * décision d'escalade, la levée — pour que le message soit durable au même instant
 * que la décision. L'envoi ({@link #dispatchPending}) est un autre temps, porté par
 * un balayage : il sort les messages en attente, les remet au bon canal, et
 * enregistre ce qui s'est passé.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    /** Priorité d'une alerte : elle passe devant tout le reste. */
    public static final int PRIORITE_ALERTE = 0;
    /** Priorité d'un e-mail de version longue : juste après l'alerte immédiate. */
    public static final int PRIORITE_EMAIL = 1;

    /** Au-delà, le message est déclaré en échec plutôt que réessayé indéfiniment. */
    private static final int MAX_ESSAIS = 5;
    /** Taille d'un lot de balayage. */
    private static final int LOT = 50;

    private final OutboxMessageRepository repository;
    private final SmsService smsService;
    private final ResendEmailService emailService;

    // ------------------------------------------------------------------ dépôt

    @Transactional
    public void enqueueSms(String toE164, String body, int priority, UUID watchId) {
        repository.save(OutboxMessage.sms(toE164, body, priority, watchId));
    }

    @Transactional
    public void enqueueEmail(String address, String subject, String html, int priority, UUID watchId) {
        repository.save(OutboxMessage.email(address, subject, html, priority, watchId));
    }

    // ------------------------------------------------------------------ envoi

    /**
     * Envoie ce qui attend, du plus prioritaire au plus ancien.
     *
     * <p>Chaque message est traité dans sa propre transaction : l'échec d'un envoi
     * ne doit pas annuler le succès des autres du même lot, ni faire rejouer un
     * message déjà parti. Un envoi refusé laisse le message en attente jusqu'à
     * épuisement des essais, puis le marque en échec — un échec est fait pour être
     * vu, pas retenté sans fin.
     *
     * @return le nombre de messages effectivement remis à un fournisseur
     */
    @Transactional
    public int dispatchPending() {
        List<OutboxMessage> lot = repository.findByStatusOrderByPriorityAscCreatedAtAsc(
            OutboxStatus.PENDING, PageRequest.of(0, LOT));

        int envoyes = 0;
        Instant now = Instant.now();
        for (OutboxMessage message : lot) {
            if (envoyer(message, now)) {
                envoyes++;
            }
        }
        return envoyes;
    }

    private boolean envoyer(OutboxMessage message, Instant now) {
        try {
            return switch (message.getChannel()) {
                case SMS -> {
                    SmsService.SmsSendResult r = smsService.send(message.getRecipient(), message.getBody());
                    if (r.accepted()) {
                        message.markSent(r.providerMessageId(), now);
                        yield true;
                    }
                    message.markAttemptFailed(now, MAX_ESSAIS);
                    yield false;
                }
                case EMAIL -> {
                    boolean sent = emailService.sendHtmlEmail(
                        message.getRecipient(), message.getSubject(), message.getBody());
                    if (sent) {
                        message.markSent(null, now);
                        yield true;
                    }
                    message.markAttemptFailed(now, MAX_ESSAIS);
                    yield false;
                }
            };
        } catch (RuntimeException e) {
            log.error("Envoi outbox {} en échec ({}): {}",
                message.getId(), message.getChannel(), e.getMessage());
            message.markAttemptFailed(now, MAX_ESSAIS);
            return false;
        }
    }
}
