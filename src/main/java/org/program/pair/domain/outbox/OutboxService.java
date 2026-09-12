package org.program.pair.domain.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.sms.SmsService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
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
 *
 * <p><b>L'envoi tient en trois temps, et aucun ne recouvre l'autre.</b>
 * {@link OutboxClaimer} réclame un lot sous verrou et valide ; le fournisseur est
 * appelé <b>hors transaction</b>, donc sans tenir de connexion ;
 * {@link OutboxConfirmer} écrit l'issue de chaque message dans une transaction à
 * lui. Cette séparation est l'objet du lot 1 de P-BA-03 : elle ferme d'un coup
 * les trois défauts que l'audit relevait — le lot entier dans une transaction
 * (une exception annulait les envois déjà acceptés), l'absence de verrou (deux
 * instances remettaient le même message deux fois) et la connexion tenue pendant
 * un appel HTTP.
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

    /** Le lot, réclamé sous verrou. Bean distinct : il faut passer par le proxy. */
    private final OutboxClaimer claimer;

    /** L'issue d'un envoi, écrite message par message. Bean distinct, même raison. */
    private final OutboxConfirmer confirmer;

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
     * réclamation ne reprend que les messages échus. Au bout de
     * {@link #MAX_ESSAIS} essais — un peu plus de deux heures de panne — le
     * message passe en échec : un échec est fait pour être vu, pas retenté sans
     * fin.
     *
     * <p><b>Cette méthode n'a pas de transaction, et c'est le point.</b> Elle en
     * ouvre deux courtes par message — la réclamation, puis la confirmation — et
     * n'en tient aucune pendant l'appel au fournisseur. Un appel HTTP de trois
     * secondes ne retire donc plus une connexion du pool pendant trois secondes,
     * et une panne fournisseur qui fait traîner cinquante appels n'immobilise
     * plus rien. La contrepartie est qu'<b>une exception ne défait plus rien</b> :
     * ce qui est écrit est validé, message par message. C'est ce qu'on veut — un
     * message que le fournisseur a accepté ne doit pas repartir parce qu'un autre
     * a échoué.
     *
     * <p><b>Ce que la séquence garantit, et ce qu'elle ne garantit pas.</b>
     * L'essai est compté à la réclamation, avant le moindre appel réseau : un
     * balayage tué en plein envoi consomme un essai, et le message repart après
     * expiration de son verrou. C'est une remise <b>« au moins une fois »</b> —
     * si le fournisseur a accepté mais que la confirmation n'a pas pu être
     * écrite, le message sera remis une seconde fois. Le fermer demanderait une
     * clé d'idempotence côté fournisseur (étape 7 de P-BA-03, non livrée : la
     * documentation de Resend la décrit, celle du fournisseur SMS non).
     *
     * <p><b>La lecture se fait par projection</b> ({@link MessageAEnvoyer}) et
     * non par entité : hors transaction, une entité serait détachée, et toute
     * association paresseuse qu'on lui ajouterait lèverait dans ce balayage —
     * {@code open-in-view} ne couvre que les requêtes web.
     *
     * @return le nombre de messages effectivement remis à un fournisseur
     */
    public int dispatchPending() {
        List<UUID> reclames = claimer.reclamer(LOT, Instant.now());

        int envoyes = 0;
        for (UUID id : reclames) {
            try {
                // Relu sous SENDING : si un autre balayage a pris le relais après
                // expiration du verrou et a déjà conclu, il n'y a plus rien à
                // envoyer et la projection est vide.
                MessageAEnvoyer message = repository.findAEnvoyer(id, OutboxStatus.SENDING)
                    .orElse(null);
                if (message == null) {
                    log.debug("Outbox : message {} n'est plus à envoyer, réclamation abandonnée", id);
                    continue;
                }

                OutboxConfirmer.Resultat resultat = appelerLeFournisseur(message);
                confirmer.confirmer(id, resultat, Instant.now());
                if (resultat.accepte()) {
                    envoyes++;
                }
            } catch (RuntimeException e) {
                // Un message qui lève n'emporte plus que le sien : les autres du
                // lot ont déjà été confirmés, et celui-ci repartira à l'expiration
                // de son verrou. Ni destinataire ni corps dans le journal.
                log.error("Outbox : message {} — envoi ou confirmation en échec : {}",
                    id, e.getMessage());
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
     * Remet le message à son canal, et rend ce que le fournisseur a répondu.
     *
     * <p><b>Aucune écriture ici, et aucune transaction.</b> C'est le seul endroit
     * du balayage où l'on parle au réseau, et rien d'autre ne doit s'y passer :
     * l'{@link OutboxConfirmer} écrira l'issue ensuite, dans sa propre
     * transaction. Une exception du fournisseur est rattrapée et devient un
     * refus — donc un nouvel essai plus tard, jamais un message perdu.
     *
     * <p>Le journal ne porte ni destinataire ni corps : un message d'outbox
     * transporte un nom, un lieu, une heure, et le numéro ou l'adresse d'un
     * proche, alors que la trace vit plus longtemps que la ligne, que la purge
     * efface à sept jours.
     */
    private OutboxConfirmer.Resultat appelerLeFournisseur(MessageAEnvoyer message) {
        try {
            return switch (message.channel()) {
                case SMS -> {
                    SmsService.SmsSendResult r = smsService.send(message.recipient(), message.body());
                    yield r.accepted()
                        ? OutboxConfirmer.Resultat.accepte(r.providerMessageId())
                        : OutboxConfirmer.Resultat.refuse(r.error());
                }
                case EMAIL -> {
                    // On garde l'identifiant Resend : c'est lui que l'accusé de
                    // remise (webhook) rappellera pour dire « arrivé » ou « rebondi ».
                    String id = emailService.sendHtmlEmailReturningId(
                        message.recipient(), message.subject(), message.body());
                    yield id != null
                        ? OutboxConfirmer.Resultat.accepte(id)
                        : OutboxConfirmer.Resultat.refuse("Resend n'a pas accepté le message");
                }
            };
        } catch (RuntimeException e) {
            log.error("Envoi outbox {} en échec ({}): {}",
                message.id(), message.channel(), e.getMessage());
            return OutboxConfirmer.Resultat.refuse(e.getMessage());
        }
    }
}
