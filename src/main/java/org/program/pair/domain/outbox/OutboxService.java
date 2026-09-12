package org.program.pair.domain.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.sms.SmsService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
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
    /**
     * Priorité d'un e-mail de vérification : après tout ce qui touche à une
     * veille. Quelqu'un qui attend son lien d'inscription peut attendre dix
     * secondes de plus ; quelqu'un dont le proche n'est pas rentré, non.
     */
    public static final int PRIORITE_VERIFICATION = 2;

    /**
     * Au-delà, le message est déclaré en échec plutôt que réessayé indéfiniment.
     *
     * <p><b>Dix, et non cinq.</b> Le balayage passe toutes les dix secondes ;
     * tant que les essais se suivaient sans délai, cinq essais s'épuisaient en
     * moins d'une minute, et une panne fournisseur d'une minute suffisait à
     * déclarer une alerte définitivement en échec. Avec le délai croissant de
     * {@link OutboxMessage#markAttemptFailed} (30 s, 1 min, 2, 4, 8, 16, puis
     * 30 min de plafond), dix essais couvrent un peu plus de deux heures de
     * panne — sans tenir la file ouverte au-delà.
     */
    public static final int MAX_ESSAIS = 10;
    /** Taille d'un lot de balayage. */
    private static final int LOT = 50;

    private final OutboxMessageRepository repository;
    private final SmsService smsService;
    private final ResendEmailService emailService;

    /**
     * Pour reporter sur le compte ce qu'un e-mail de vérification devient.
     *
     * <p>L'état pourrait se lire depuis cette table, comme {@code alertDelivery}
     * le fait pour une veille — mais la purge efface les messages partis depuis
     * sept jours, et l'état retomberait alors à {@code NONE} sur un compte dont
     * l'adresse avait rebondi. Il vit donc sur le compte, et l'outbox l'y pousse.
     */
    private final UserRepository userRepository;

    // ------------------------------------------------------------------ dépôt

    @Transactional
    public void enqueueSms(String toE164, String body, int priority, UUID watchId) {
        repository.save(OutboxMessage.sms(toE164, body, priority, watchId));
    }

    @Transactional
    public void enqueueEmail(String address, String subject, String html, int priority, UUID watchId) {
        repository.save(OutboxMessage.email(address, subject, html, priority, watchId));
    }

    /**
     * Dépose l'e-mail de vérification d'un compte, et pose son état à
     * {@code PENDING}.
     *
     * <p>Le corps arrive composé : la langue se lit sur le fil de la requête,
     * qui n'existe plus au moment du balayage.
     *
     * <p><b>Le destinataire est passé à part, et n'est pas toujours l'adresse du
     * compte.</b> Un changement d'adresse envoie son lien à l'adresse
     * <i>demandée</i> — c'est tout l'intérêt : elle seule peut prouver qu'elle
     * existe et qu'elle reçoit. L'état, lui, reste celui du compte, car c'est le
     * même écran qui le lit.
     *
     * <p><b>L'identifiant du message précédent est effacé.</b> Un renvoi rend
     * caduc ce qu'on savait de l'envoi d'avant, et c'est cet effacement qui fait
     * qu'un accusé tardif portant sur l'ancien message ne viendra pas écraser le
     * sort du nouveau.
     */
    @Transactional
    public void enqueueVerificationEmail(User user, String recipient, String subject, String html) {
        repository.save(OutboxMessage.verificationEmail(
            user.getId(), recipient, subject, html, PRIORITE_VERIFICATION));
        user.setVerificationEmailMessageId(null);
        user.setVerificationEmailDelivery(VerificationEmailDelivery.PENDING);
        userRepository.save(user);
    }

    // ------------------------------------------------------------------ envoi

    /**
     * Envoie ce qui attend et dont l'heure du prochain essai est venue, du plus
     * prioritaire au plus ancien.
     *
     * <p><b>Un envoi refusé n'est pas réessayé tout de suite.</b>
     * {@link OutboxMessage#markAttemptFailed} pose une date de prochain essai
     * dont le délai double (30 s, 1 min, 2, 4, ... plafonné à 30 min), et la
     * lecture ci-dessous n'en reprend que les messages échus. Au bout de
     * {@link #MAX_ESSAIS} essais — un peu plus de deux heures de panne — le
     * message passe en échec : un échec est fait pour être vu, pas retenté sans
     * fin.
     *
     * <p><b>Ce que cette méthode ne fait pas encore.</b> Sa javadoc a longtemps
     * promis « chaque message dans sa propre transaction » ; c'était faux. Tout
     * le lot tient dans <i>une seule</i> transaction — celle de cette méthode —
     * et les appels au fournisseur s'y font, connexion tenue. Donc aujourd'hui :
     * une exception qui s'échapperait d'ici annulerait les écritures de tout le
     * lot, y compris celles des messages déjà remis (les {@code markSent} ne
     * seraient pas écrits alors que le fournisseur, lui, a bien accepté). Aucune
     * lecture n'est faite sous {@code FOR UPDATE SKIP LOCKED} : deux instances
     * qui balaient en même temps peuvent remettre le même message deux fois.
     * Ces deux défauts sont traités au lot 1 de P-BA-03 (réclamation sous
     * verrou, envoi hors transaction, confirmation message par message) ; ce
     * lot-ci ne traite que le délai entre essais.
     *
     * @return le nombre de messages effectivement remis à un fournisseur
     */
    @Transactional
    public int dispatchPending() {
        Instant now = Instant.now();
        List<OutboxMessage> lot = repository.findAEnvoyer(
            OutboxStatus.PENDING, now, PageRequest.of(0, LOT));

        int envoyes = 0;
        for (OutboxMessage message : lot) {
            if (envoyer(message, now)) {
                envoyes++;
            }
        }
        return envoyes;
    }

    /**
     * Enregistre ce qu'un accusé de remise rapporte, en recoupant par l'identifiant
     * fournisseur.
     *
     * <p>On ne régresse pas un état plus avancé vers un état transitoire : un
     * {@code DELIVERED} déjà reçu n'est pas ramené à {@code DELAYED} par un
     * événement en retard, et un {@code BOUNCED} ou {@code COMPLAINED} — le fait
     * qui compte — n'est jamais écrasé. Les événements qu'on ne suit pas (ouverture,
     * clic) sont ignorés en silence.
     */
    @Transactional
    public void recordDelivery(String providerMessageId, String eventType) {
        OutboxDelivery nouveau = switch (eventType == null ? "" : eventType) {
            case "email.delivered" -> OutboxDelivery.DELIVERED;
            case "email.bounced" -> OutboxDelivery.BOUNCED;
            case "email.complained" -> OutboxDelivery.COMPLAINED;
            case "email.delivery_delayed" -> OutboxDelivery.DELAYED;
            default -> null;
        };
        if (nouveau == null || providerMessageId == null) {
            return;
        }
        repository.findByProviderMessageId(providerMessageId).ifPresent(message -> {
            OutboxDelivery actuel = message.getDeliveryState();
            if (actuel == OutboxDelivery.BOUNCED || actuel == OutboxDelivery.COMPLAINED) {
                return; // fait terminal, on ne l'écrase pas.
            }
            if (nouveau == OutboxDelivery.DELAYED && actuel == OutboxDelivery.DELIVERED) {
                return; // pas de régression d'un arrivé vers un retardé.
            }
            message.setDeliveryState(nouveau);
            reporterSurLeCompte(message, VerificationEmailDelivery.depuis(nouveau));
        });
    }

    /**
     * Reporte sur le compte ce qu'on vient d'apprendre d'un e-mail de
     * vérification. Sans effet pour tout autre message.
     *
     * <p><b>Le message doit être celui que le compte attend.</b> Un rebond
     * concernant le premier renvoi peut arriver après que le second a été
     * délivré — les accusés ne sont pas ordonnés. Comparer les identifiants est
     * ce qui empêche un fait périmé de faire mentir l'écran ; sans cela, le
     * bouton « renvoyer » aggraverait l'affichage au lieu de le corriger.
     */
    private void reporterSurLeCompte(OutboxMessage message, VerificationEmailDelivery candidat) {
        if (message.getPurpose() != OutboxPurpose.EMAIL_VERIFICATION
                || message.getUserId() == null || candidat == null) {
            return;
        }
        userRepository.findById(message.getUserId()).ifPresent(user -> {
            if (!java.util.Objects.equals(
                    user.getVerificationEmailMessageId(), message.getProviderMessageId())) {
                return; // accusé portant sur un envoi que ce compte a remplacé.
            }
            if (user.getVerificationEmailDelivery().cedeLaPlaceA(candidat)) {
                user.setVerificationEmailDelivery(candidat);
                userRepository.save(user);
            }
        });
    }

    /**
     * L'issue de la remise au fournisseur, portée sur le compte.
     *
     * <p>{@code SENT} dès que Resend accepte, {@code FAILED} quand les essais
     * sont épuisés — ou qu'il a refusé tout de suite, ce que produirait un compte
     * d'envoi resté en mode d'essai. Tant que des essais restent, l'état ne bouge
     * pas : {@code PENDING} est exact, et afficher un échec réparable serait
     * inviter à corriger une adresse qui n'a rien.
     */
    private void reporterLEnvoiSurLeCompte(OutboxMessage message) {
        if (message.getPurpose() != OutboxPurpose.EMAIL_VERIFICATION
                || message.getUserId() == null) {
            return;
        }
        userRepository.findById(message.getUserId()).ifPresent(user -> {
            if (message.getStatus() == OutboxStatus.SENT) {
                user.setVerificationEmailMessageId(message.getProviderMessageId());
                user.setVerificationEmailDelivery(VerificationEmailDelivery.SENT);
                userRepository.save(user);
            } else if (message.getStatus() == OutboxStatus.FAILED) {
                user.setVerificationEmailDelivery(VerificationEmailDelivery.FAILED);
                userRepository.save(user);
            }
        });
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
                    echecDEssai(message, now);
                    yield false;
                }
                case EMAIL -> {
                    // On garde l'identifiant Resend : c'est lui que l'accusé de
                    // remise (webhook) rappellera pour dire « arrivé » ou « rebondi ».
                    String id = emailService.sendHtmlEmailReturningId(
                        message.getRecipient(), message.getSubject(), message.getBody());
                    if (id != null) {
                        message.markSent(id, now);
                        reporterLEnvoiSurLeCompte(message);
                        yield true;
                    }
                    echecDEssai(message, now);
                    reporterLEnvoiSurLeCompte(message);
                    yield false;
                }
            };
        } catch (RuntimeException e) {
            log.error("Envoi outbox {} en échec ({}): {}",
                message.getId(), message.getChannel(), e.getMessage());
            echecDEssai(message, now);
            reporterLEnvoiSurLeCompte(message);
            return false;
        }
    }

    /**
     * Compte un essai manqué, et journalise l'abandon s'il était le dernier.
     *
     * <p><b>Le journal ne porte ni destinataire ni corps.</b> Un message
     * d'outbox transporte un nom, un lieu, une heure, et le numéro ou l'adresse
     * d'un proche ; le journal, lui, est conservé plus longtemps que le message
     * — la purge efface la ligne à sept jours, pas la trace. On y met de quoi
     * retrouver la ligne tant qu'elle existe (l'identifiant), de quoi juger de
     * la gravité (le canal, le nombre d'essais) et de quoi rattacher l'abandon à
     * ce qui l'a produit (la veille). Rien d'autre.
     *
     * <p>L'abandon est un {@code error} et non un {@code warn} : c'est une
     * alerte qui ne partira pas, et personne n'en sera averti autrement tant que
     * la métrique {@code outbox.failed} du lot 1 n'existe pas.
     */
    private void echecDEssai(OutboxMessage message, Instant now) {
        message.markAttemptFailed(now, MAX_ESSAIS);
        if (message.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox : message {} abandonné après {} essais ({}, veille {})",
                message.getId(), message.getAttempts(), message.getChannel(), message.getWatchId());
        }
    }
}
